package haven.automated.nbots;

import haven.Button;
import haven.Coord;
import haven.Coord2d;
import haven.FlowerMenu;
import haven.GameUI;
import haven.Gob;
import haven.Loading;
import haven.Makewindow;
import haven.MenuGrid;
import haven.ResDrawable;
import haven.UI;
import haven.Widget;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.core.Widgets;
import haven.automated.nbots.world.BotNav;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static haven.OCache.posres;

/**
 * Smokes and raids every wild beehive in sight.
 *
 * <p>Wild beehives (as opposed to the domestic skeps a beekeeper builds) can only be raided
 * after they have been pacified with smoke. The ritual is: stand a lit Bough Pyre under the
 * hive, wait for the swarm to leave, then raid. nurgling2's BoughBee only does the last step,
 * and only for one hive; it expects the player to have already built and lit the pyre by hand.
 * This bot owns the whole sequence and walks all visible hives before going home.
 *
 * <p>Per hive it: (1) ensures a pyre is standing under the hive, building one from gathered
 * boughs if not, (2) tops up stamina, (3) crafts a firebrand from gathered branches, (4) lights
 * the pyre, (5) waits until the pyre has burned out and the hive's swarm marker is gone, then
 * (6) raids it and moves on to the next hive.
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
    private static final String TREE_RES = "gfx/terobjs/tree";
    private static final String BRANCH_RES = "items/branch";
    private static final String BOUGH_PETAL = "Take bough";
    private static final String PYRE_MENU = "Bough Pyre";
    private static final String FIREBRAND_MENU = "Firebrand";
    private static final String LIGHT_PETAL = "Light";
    private static final String RAID_PETAL = "Raid!";

    /** How close (world units, ~2 tiles) a pyre must be to a hive to count as "under" it. */
    private static final double PYRE_RANGE = 22.0;
    /** How long to wait for the swarm to leave before giving up on a hive, in ticks (~25ms each). */
    private static final int QUIET_TIMEOUT = 12000;

    public NBeeSmokerBot(GameUI gui) {
        super(gui, "NBeeSmokerBot", "Bee Smoker (crew)", "bee-smoker", UI.scale(300, 170));
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

    @Override
    protected Outcome work() throws InterruptedException {
        List<Gob> hives = hives();
        if (hives.isEmpty())
            return Outcome.failed("no wild beehives in sight");
        report("smoking " + hives.size() + " wild beehive(s)");
        int done = 0;
        for (Gob hive : hives) {
            if (!running())
                return Outcome.ok();
            Outcome o = smoke(hive);
            if (o.isOk())
                done++;
            else
                report("skipped a hive: " + o.reason);
        }
        report("smoked " + done + " of " + hives.size() + " hives");
        return Outcome.ok();
    }

    /** Every wild beehive currently streamed, nearest first. */
    private List<Gob> hives() {
        List<Gob> out = new ArrayList<>();
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                if (resname(g).contains(HIVE_RES))
                    out.add(g);
            }
        }
        Gob me = nav.player();
        out.sort((a, b) -> Double.compare(a.rc.dist(me.rc), b.rc.dist(me.rc)));
        return out;
    }

    private Outcome smoke(Gob hive) throws InterruptedException {
        report("smoking hive @" + hive.rc);
        Gob pyre = pyreNear(hive);
        if (pyre == null) {
            Outcome o = buildPyre(hive);
            if (!o.isOk())
                return o;
            pyre = pyreNear(hive);
            if (pyre == null)
                return Outcome.blocked("the pyre never appeared near the hive");
        }
        // crafting a firebrand is thirsty work; top up stamina before lighting
        nav.waitUntil(() -> ctx.stamina() >= 0.95, 3600);
        Outcome o = craftFirebrand();
        if (!o.isOk())
            return o;
        o = light(pyre);
        if (!o.isOk())
            return o;
        o = awaitQuiet(pyre, hive);
        if (!o.isOk())
            return o;
        o = raid(hive);
        if (!o.isOk())
            return o;
        report("raided hive @" + hive.rc);
        return Outcome.ok();
    }

    /** Builds and lights a fresh pyre under the hive, gathering boughs first if configured to. */
    private Outcome buildPyre(Gob hive) throws InterruptedException {
        if (settings.on("collect") && count(BOUGH) < settings.num("boughs")) {
            if (!gather(BOUGH, TREE_RES, BOUGH_PETAL, settings.num("boughs")))
                return Outcome.blocked("can't gather " + settings.num("boughs") + " boughs for the pyre");
        }
        MenuGrid.Pagina pag = pagina(PYRE_MENU);
        if (pag == null)
            return Outcome.failed("no 'Bough Pyre' option in the build menu");
        for (Coord spot : candidateSpots(hive)) {
            if (!enterPlacing(pag))
                return Outcome.blocked("build menu never entered placement mode");
            gui.map.wdgmsg("place", spot, 0, 1, 0);
            nav.waitUntil(() -> gobAtTile(spot, "consobj") != null, 40);
            Gob consobj = gobAtTile(spot, "consobj");
            if (consobj == null)
                continue; // the server rejected that tile; try the next one
            return finishBuild(consobj);
        }
        return Outcome.blocked("no free spot to place the pyre near the hive");
    }

    private Outcome finishBuild(Gob consobj) throws InterruptedException {
        Coord2d spot = consobj.rc;
        nav.approach(consobj, BotNav.REACH);
        rclick(consobj);
        nav.waitUntil(() -> findButton(gui.ui.root, "Build") != null, 40);
        Button build = findButton(gui.ui.root, "Build");
        if (build == null)
            return Outcome.blocked("no 'Build' button on the construction");
        build.click();
        nav.waitUntil(() -> resNear(spot, "pyre") != null || resNear(spot, "bonfire") != null, 400);
        return (resNear(spot, "pyre") != null || resNear(spot, "bonfire") != null)
                ? Outcome.ok()
                : Outcome.blocked("the pyre never appeared after building");
    }

    private Outcome craftFirebrand() throws InterruptedException {
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
        mw.wdgmsg("make", 0);
        nav.waitUntil(() -> ctx.onProgress(), 80);
        nav.waitUntil(() -> !ctx.onProgress(), 400);
        return Outcome.ok();
    }

    private Outcome light(Gob pyre) throws InterruptedException {
        nav.approach(pyre, BotNav.REACH);
        rclick(pyre);
        FlowerMenu fm = Widgets.awaitFlowerMenu(gui.ui.root, this::running);
        if (fm == null)
            return Outcome.blocked("no flower menu when lighting the pyre");
        if (pickPetal(fm, LIGHT_PETAL))
            return Outcome.ok();
        fm.wdgmsg("cl", -1);
        return Outcome.blocked("no 'Light' option on the pyre (got: " + petalNames(fm) + ")");
    }

    /** Waits for the pyre to burn out and the swarm to leave, keeping fed and watered meanwhile. */
    private Outcome awaitQuiet(Gob pyre, Gob hive) throws InterruptedException {
        for (int i = 0; i < QUIET_TIMEOUT; i++) {
            if (!running())
                return Outcome.ok();
            if (i % 200 == 0) {
                if (!upkeep())
                    return Outcome.failed("upkeep failed while waiting for the hive to go quiet");
                report("waiting for the hive to go quiet...");
            }
            if (nav.gob(pyre.id) == null && quiet(hive))
                return Outcome.ok();
            nav.pause(1);
        }
        return Outcome.blocked("hive still swarming after " + (QUIET_TIMEOUT / 40) + " seconds");
    }

    private Outcome raid(Gob hive) throws InterruptedException {
        nav.approach(hive, BotNav.REACH);
        rclick(hive);
        FlowerMenu fm = Widgets.awaitFlowerMenu(gui.ui.root, this::running);
        if (fm == null)
            return Outcome.blocked("no flower menu when raiding the hive");
        if (!pickPetal(fm, RAID_PETAL)) {
            fm.wdgmsg("cl", -1);
            return Outcome.blocked("no 'Raid!' option on the hive (got: " + petalNames(fm) + ")");
        }
        nav.waitUntil(() -> nav.gob(hive.id) == null, 200);
        return Outcome.ok();
    }

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
            nav.approach(source, BotNav.REACH);
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

    private MenuGrid.Pagina pagina(String name) {
        for (MenuGrid.Pagina pag : gui.menu.paginae) {
            try {
                if (name.equalsIgnoreCase(pag.button().name()))
                    return pag;
            } catch (Loading e) {
                // button resource not loaded yet; skip this pagina
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

    /** A pyre (built or lit) already standing close enough to smoke the given hive. */
    private Gob pyreNear(Gob ref) {
        return resNear(ref.rc, "pyre") != null ? resNear(ref.rc, "pyre") : resNear(ref.rc, "bonfire");
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

    private Gob gobAtTile(Coord tile, String fragment) {
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                if (g.rc.floor(posres).equals(tile) && resname(g).contains(fragment))
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

    private Gob nearest(String fragment, Set<Long> exclude) {
        Gob me = nav.player();
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

    private List<Coord> candidateSpots(Gob hive) {
        List<Coord> out = new ArrayList<>();
        Coord ht = hive.rc.floor(posres);
        for (int r = 1; r <= 3; r++)
            for (int dy = -r; dy <= r; dy++)
                for (int dx = -r; dx <= r; dx++)
                    if (Math.max(Math.abs(dx), Math.abs(dy)) == r)
                        out.add(ht.add(dx, dy));
        return out;
    }

    private Makewindow makewnd() {
        return (gui.makewnd == null) ? null : gui.makewnd.makeWidget;
    }

    private Button findButton(Widget root, String label) {
        if (root == null)
            return null;
        for (Widget w = root.child; w != null; w = w.next) {
            if (w instanceof Button && label.equals(((Button) w).text.text))
                return (Button) w;
            Button deep = findButton(w, label);
            if (deep != null)
                return deep;
        }
        return null;
    }

    private String resname(Gob g) {
        try {
            return g.getres().name;
        } catch (Loading e) {
            return "";
        }
    }

    private void rclick(Gob g) {
        gui.map.wdgmsg("click", Coord.z, g.rc.floor(posres), 3, 0, 0, (int) g.id, g.rc.floor(posres), 0, -1);
    }

    private void lclick(Coord2d wc) {
        gui.map.wdgmsg("click", Coord.z, wc.floor(posres), 1, 0);
    }
}
