package haven.automated.nbots;

import haven.CheckBox;
import haven.Config;
import haven.Coord;
import haven.GameUI;
import haven.Gob;
import haven.Loading;
import haven.Resource;
import haven.UI;
import haven.WItem;
import haven.automated.lp.NLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Cellar digging, for a crew rather than one character.
 *
 * The job itself is a loop: dig the cellar, which throws up a scatter of boulders, chip every
 * boulder away, dig again. The stock Cellar Digging Bot does exactly that and does it well for one
 * character. Run three copies of it side by side, though, and they all pick the same nearest
 * boulder, all walk to the same side of it, and all try to trigger the same dig - so most of the
 * crew spends its time shoving through each other to reach a rock somebody else already finished.
 *
 * What changes here:
 *
 * - Boulders are worked by SLOT. Several characters can chip one boulder at once - there is room
 *   around it - so the thing that gets reserved is a standing position, not the rock. A big
 *   boulder therefore absorbs several diggers and a small one turns the extras away to find their
 *   own, which is what you want either way.
 * - The dig itself is single-file. Only one character can usefully trigger the cellar's dig
 *   action, so it's guarded by a claim of its own; whoever gets it digs, and the rest wait for the
 *   boulders instead of queuing up at the door.
 * - Running out of water is a trip, not the end of the shift. See {@link WaterService}.
 * - The pickaxe is fetched rather than assumed, so a bot started with an axe in hand still works.
 */
public class NCellarDiggerBot extends NBot {
    private static final String LOG = "nbot-cellar.log";
    private static final String CELLAR_RES = "gfx/terobjs/arch/cellardoor";

    /**
     * Slot 0 on the cellar door, used as a plain mutex rather than as somewhere to stand. Digging
     * is one character's job at a time; a second character triggering it achieves nothing and
     * costs a walk.
     */
    private static final int DIG_LOCK_SLOT = 0;

    private final ToolSwap tools;
    private boolean dropStones = false;

    public NCellarDiggerBot(GameUI gui) {
        super(gui, "Cellar Digger (crew)", "nCellarDiggerBotWindow", LOG, UI.scale(200, 74));
        this.tools = new ToolSwap(gui, nav, LOG);
        add(new CheckBox("Drop stones") {
            {
                a = dropStones;
            }

            public void set(boolean val) {
                dropStones = val;
                a = val;
            }
        }, UI.scale(10, 22));
        pack();
    }

    @Override
    protected String title() {
        return "Cellar Digger";
    }

    @Override
    protected void onClosed() {
        if (gui.nCellarDiggerBot == this) {
            gui.nCellarDiggerBot = null;
            gui.nCellarDiggerThread = null;
        }
    }

    // ------------------------------------------------------------------ the shift

    @Override
    protected void runOnce() throws InterruptedException {
        if (findCellar() == null) {
            fatalStop = "no cellar door in sight - stand by the cellar you want dug.";
            return;
        }
        if (!tools.equipped(ToolSwap.Kind.PICK) && !tools.equip(ToolSwap.Kind.PICK)) {
            fatalStop = "no pickaxe - equip or carry one.";
            return;
        }

        int chipped = 0;
        int digs = 0;
        // Bounded on IDLE cycles rather than total cycles: a cycle that finds work resets it, so a
        // long shift is fine while a bot with nothing to do and no dig it can take stops instead of
        // spinning at the door forever.
        int idle = 0;

        while (running() && idle < 40) {
            if (!checkVitals())
                return;

            if (dropStones)
                dropStones();

            Gob boulder = pickBoulder();
            if (boulder != null) {
                setStatus("Chipping (" + chipped + " done, " + digs + " digs)");
                if (chip(boulder))
                    chipped++;
                idle = 0;
                continue;
            }

            // Nothing left to chip. Either it's our turn to dig, or somebody else is already on it
            // and boulders are about to appear.
            Gob cellar = findCellar();
            if (cellar == null) {
                fatalStop = "the cellar door is gone.";
                return;
            }
            if (WorkClaims.claim(cellar.id, DIG_LOCK_SLOT)) {
                try {
                    setStatus("Digging (" + chipped + " chipped, " + digs + " digs)");
                    if (dig(cellar)) {
                        digs++;
                        idle = 0;
                        continue;
                    }
                } finally {
                    WorkClaims.release(cellar.id, DIG_LOCK_SLOT);
                }
            } else {
                setStatus("Waiting for another digger to break ground.");
            }
            idle++;
            nav.pause(20);
        }

        report("finished: " + chipped + " boulder(s) chipped over " + digs + " dig(s).");
        setStatus("Done: " + chipped + " chipped.");
    }

    // ------------------------------------------------------------------ boulders

    /**
     * The nearest boulder we can actually get a slot on.
     *
     * Tried in distance order and skipped rather than waited on, so a boulder that is already
     * ringed with diggers doesn't hold up a bot that could be usefully chipping the next one along.
     */
    private Gob pickBoulder() throws InterruptedException {
        for (Gob g : bouldersByDistance()) {
            if (!running())
                throw new InterruptedException();
            if (takeSlotAt(g))
                return g;
        }
        return null;
    }

    private List<Gob> bouldersByDistance() {
        List<Gob> out = new ArrayList<>();
        Gob me = gui.map.player();
        if (me == null)
            return out;
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                try {
                    Resource res = g.getres();
                    if (res != null && res.name.contains("/bumlings/") && !exhausted.contains(g.id))
                        out.add(g);
                } catch (Loading | NullPointerException ignored) {
                }
            }
        }
        out.sort((a, b) -> Double.compare(me.rc.dist(a.rc), me.rc.dist(b.rc)));
        return out;
    }

    /**
     * Chips one boulder until it's gone (or we can't carry on).
     *
     * @return true if the boulder was destroyed.
     */
    private boolean chip(Gob boulder) throws InterruptedException {
        long id = boulder.id;
        try {
            if (!inRange(boulder))
                return false;
            clearHand();
            if (rclickAndChoose(boulder, "Chip", "Mine", "Chip stone") == null) {
                // The menu offered nothing we recognise. Reported by rclickAndChoose; give this
                // boulder a rest rather than immediately picking it again on the next pass.
                exhausted.add(id);
                return false;
            }

            int stuck = 0;
            while (running() && nav.gob(id) != null) {
                renewSlot();
                if (!checkVitals())
                    return false;
                if (dropStones && freeSpace() == 0)
                    dropStones();

                if (mining()) {
                    stuck = 0;
                } else if (++stuck >= 6) {
                    // Stopped swinging with the boulder still standing: interrupted by a drink, a
                    // full pack, or somebody walking through us. One re-issue, then move on.
                    if (rclickAndChoose(nav.gob(id), "Chip", "Mine", "Chip stone") == null)
                        return false;
                    stuck = 0;
                }
                nav.pause(4);
            }
            return nav.gob(id) == null;
        } finally {
            releaseSlot();
        }
    }

    private boolean mining() {
        return poseContains("pickan") || (gui.prog != null && gui.prog.prog >= 0);
    }

    /** Boulders whose menu we didn't understand, so one odd object can't stall the whole shift. */
    private final java.util.Set<Long> exhausted = new java.util.HashSet<>();

    // ------------------------------------------------------------------ digging

    /**
     * Triggers the cellar's own dig. Held under the dig lock by the caller.
     *
     * @return true if boulders appeared, i.e. there was more cellar to dig.
     */
    private boolean dig(Gob cellar) throws InterruptedException {
        if (!nav.approach(cellar, BotNav.REACH)) {
            NLog.log(LOG, "couldn't reach the cellar door");
            return false;
        }
        clearHand();
        Gob door = nav.gob(cellar.id);
        if (door == null)
            return false;
        if (rclickAndChoose(door, "Dig", "Dig out", "Enter") == null)
            return false;

        // Wait for the dig to produce something. A cellar that is fully dug simply yields nothing,
        // which is how the shift ends - so this returning false is a normal outcome, not a fault.
        final int before = bouldersByDistance().size();
        nav.waitUntil(() -> bouldersByDistance().size() > before, 200);
        boolean progressed = bouldersByDistance().size() > before;
        NLog.log(LOG, progressed ? "dig produced new boulders" : "dig produced nothing");
        return progressed;
    }

    // ------------------------------------------------------------------ inventory

    private int freeSpace() {
        try {
            return (gui.maininv == null) ? -1 : gui.maininv.getFreeSpace();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Throws chipped stone on the floor so a full pack can't halt the dig. */
    private void dropStones() {
        if (gui.maininv == null)
            return;
        for (WItem wi : gui.maininv.getAllItems()) {
            try {
                if (Config.stoneItemBaseNames.contains(wi.item.resource().basename()))
                    wi.item.wdgmsg("drop", new Coord(wi.sz.x / 2, wi.sz.y / 2));
            } catch (Loading | NullPointerException ignored) {
            }
        }
    }

    private Gob findCellar() {
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                try {
                    Resource res = g.getres();
                    if (res != null && CELLAR_RES.equals(res.name))
                        return g;
                } catch (Loading | NullPointerException ignored) {
                }
            }
        }
        return null;
    }
}
