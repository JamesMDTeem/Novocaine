package haven.automated.nbots;

import haven.Config;
import haven.Coord;
import haven.GameUI;
import haven.Gob;
import haven.Loading;
import haven.Resource;
import haven.UI;
import haven.WItem;
import haven.automated.nbots.core.Alias;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.task.Approach;
import haven.automated.nbots.task.TakeWorkSlot;
import haven.automated.nbots.task.ToolSwap;
import haven.automated.nbots.task.WorkGob;
import haven.automated.nbots.world.WorkClaims;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cellar digging, for a crew rather than one character.
 *
 * The job is a loop: dig the cellar, which throws up a scatter of boulders, chip every boulder
 * away, dig again. The stock bot does that well for one character; run three copies side by side
 * and they all pick the same nearest boulder, all walk to the same side of it, and all try to
 * trigger the same dig.
 *
 * Two things make that work here, and neither is this class's own code:
 *
 * - Boulders are worked by SLOT ({@link TakeWorkSlot}). Several characters can chip one boulder at
 *   once given room, so what gets reserved is a standing position, not the rock. A big boulder
 *   absorbs several diggers; a small one turns the extras away to find their own.
 * - The dig itself is single-file, guarded by a claim of its own. Only one character can usefully
 *   trigger it, so the rest wait for boulders rather than queuing at the door.
 *
 * Everything else - approach, menu, keeping at it, drinking, eating, coming back - is a shared
 * task. What is left below is only what makes this bot the cellar digger.
 */
public class NCellarDiggerBot extends NBot {
    private static final String LOG = "nbot-cellar.log";
    private static final String CELLAR_RES = "gfx/terobjs/arch/cellardoor";
    private static final Alias BOULDER = new Alias("boulder", "/bumlings/");

    /**
     * The dig claim. Slot 0 on the cellar door used as a plain mutex rather than somewhere to
     * stand - digging is one character's job at a time, and a second one triggering it achieves
     * nothing and costs a walk.
     */
    private static int DIG_SLOT = 0;

    /** Boulders whose menu we didn't understand, so one odd object can't stall the shift. */
    private final Set<Long> retired = new HashSet<>();

    public NCellarDiggerBot(GameUI gui) {
        super(gui, "NCellarDiggerBot", "Cellar Digger (crew)", LOG, UI.scale(210, 78));
        settings.flag("dropstones", "Drop stones", false);
        settings.layout(this, UI.scale(10, 22), 1, 0);
        pack();
    }

    @Override
    protected String title() {
        return "Cellar Digger";
    }

    // ------------------------------------------------------------------ the shift

    @Override
    protected Outcome work() throws InterruptedException {
        if (findCellar() == null)
            return Outcome.failed("no cellar door in sight - stand by the cellar you want dug");

        Outcome pick = tools.require(ToolSwap.Kind.PICK);
        if (!pick.isOk())
            return pick;

        int chipped = 0;
        int digs = 0;
        // Bounded on IDLE cycles, not total: a cycle that finds work resets it, so a long shift is
        // fine while a bot with nothing to do and no dig it can take stops rather than spinning at
        // the door forever.
        int idle = 0;

        while (running() && idle < 40) {
            if (!upkeep())
                return Outcome.failed(fatalStop);
            if (settings.on("dropstones"))
                dropStones();

            Boulder next = takeBoulder();
            if (next != null) {
                setStatus("Chipping (" + chipped + " done, " + digs + " digs)");
                try {
                    Outcome o = WorkGob.menu(next.gob, next.slot, "Chip", "Mine", "Chip stone")
                        .withTick(this::dropStonesIfFull)
                        .run(ctx);
                    if (o.isOk())
                        chipped++;
                    else if (o.isFailed())
                        retired.add(next.gob.id);
                } finally {
                    next.slot.release();
                }
                idle = 0;
                continue;
            }

            // Nothing left to chip. Either it's our turn to dig, or somebody else is on it and
            // boulders are about to appear.
            Gob cellar = findCellar();
            if (cellar == null)
                return Outcome.failed("the cellar door is gone");
            String digKey = WorkClaims.slotKey(cellar.id, DIG_SLOT);
            if (WorkClaims.claim(digKey)) {
                try {
                    setStatus("Digging (" + chipped + " chipped, " + digs + " digs)");
                    if (dig(cellar)) {
                        digs++;
                        idle = 0;
                        continue;
                    }
                } finally {
                    WorkClaims.release(digKey);
                }
            } else {
                setStatus("Waiting for another digger to break ground.");
            }
            idle++;
            nav.pause(20);
        }

        report("finished: " + chipped + " boulder(s) chipped over " + digs + " dig(s).");
        setStatus("Done: " + chipped + " chipped.");
        return Outcome.ok();
    }

    // ------------------------------------------------------------------ boulders

    private static final class Boulder {
        final Gob gob;
        final TakeWorkSlot slot;

        Boulder(Gob gob, TakeWorkSlot slot) {
            this.gob = gob;
            this.slot = slot;
        }
    }

    /**
     * The nearest boulder we can get a slot on, already stood in.
     *
     * Tried in distance order and SKIPPED rather than waited on, so a boulder already ringed with
     * diggers doesn't hold up a bot that could be usefully chipping the next one along.
     */
    private Boulder takeBoulder() throws InterruptedException {
        for (Gob g : bouldersByDistance()) {
            if (!running())
                throw new InterruptedException();
            TakeWorkSlot slot = new TakeWorkSlot(g);
            if (slot.run(ctx).isOk())
                return new Boulder(g, slot);
            slot.release();
        }
        return null;
    }

    private List<Gob> bouldersByDistance() {
        List<Gob> out = new ArrayList<>();
        Gob me = ctx.player();
        if (me == null)
            return out;
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                try {
                    Resource res = g.getres();
                    if (res != null && BOULDER.matchesPart(res.name) && !retired.contains(g.id))
                        out.add(g);
                } catch (Loading | NullPointerException ignored) {
                }
            }
        }
        out.sort((a, b) -> Double.compare(me.rc.dist(a.rc), me.rc.dist(b.rc)));
        return out;
    }

    // ------------------------------------------------------------------ digging

    /**
     * Triggers the cellar's own dig. Held under the dig claim by the caller.
     *
     * @return true if boulders appeared, i.e. there was more cellar to dig. Returning false is a
     *         normal outcome - a fully dug cellar simply yields nothing - not a fault.
     */
    private boolean dig(Gob cellar) throws InterruptedException {
        if (!new Approach(cellar).run(ctx).isOk())
            return false;
        Gob door = ctx.gob(cellar.id);
        if (door == null)
            return false;

        final int before = bouldersByDistance().size();
        Outcome o = WorkGob.menu(door, null, "Dig", "Dig out", "Enter").run(ctx);
        if (o.isFailed())
            return false;
        nav.waitUntil(() -> bouldersByDistance().size() > before, 200);
        boolean progressed = bouldersByDistance().size() > before;
        ctx.log(progressed ? "dig produced new boulders" : "dig produced nothing");
        return progressed;
    }

    // ------------------------------------------------------------------ inventory

    private void dropStonesIfFull() {
        if (settings.on("dropstones") && ctx.freeSpace() == 0)
            dropStones();
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
