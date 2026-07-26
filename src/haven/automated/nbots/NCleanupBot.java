package haven.automated.nbots;

import haven.CheckBox;
import haven.Config;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.Loading;
import haven.UI;
import haven.WItem;
import haven.automated.lp.NLog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static haven.OCache.posres;

/**
 * Clearing a site - trees, bushes, boulders, stumps and soil piles - with the tool swapping and
 * the crowd handling the stock Cleanup Bot doesn't do.
 *
 * Three things were worth rebuilding rather than patching:
 *
 * TOOLS. The five checkboxes need three different two-handed tools between them: an axe to chop, a
 * pickaxe to chip stone, a shovel to clear stumps and soil. A character holds one at a time, so
 * the stock bot silently accomplishes nothing on any job that doesn't match whatever was already
 * in hand. Here the tool is part of the task: pick a target, make sure its tool is held (see
 * {@link ToolSwap}), then act. Targets whose tool can't be found are dropped from the plan with one
 * message rather than retried forever.
 *
 * APPROACH. The stock bot paths to a fixed offset from the target ({@code gob.rc.add(20, 0)}),
 * which is a point 20 units due east whether or not that is inside the tree, in a lake, or off a
 * cliff, and then gives up if the resulting distance check fails. This walks to a slot chosen
 * around the object and falls back to pathing at the object itself.
 *
 * CROWD. Targets are worked by slot, so several bots can clear one grove together without
 * converging on the same trunk from the same side. See {@link WorkSlots} and {@link WorkClaims}.
 *
 * The stock Cleanup Bot is untouched and still on the Bots tab.
 */
public class NCleanupBot extends NBot {
    private static final String LOG = "nbot-cleanup.log";

    /** What a target is, which decides both the tool and the action. */
    private enum Job {
        TREE(ToolSwap.Kind.AXE, new String[] {"Chop"}),
        BUSH(ToolSwap.Kind.AXE, new String[] {"Chop", "Destroy"}),
        ROCK(ToolSwap.Kind.PICK, new String[] {"Chip", "Mine", "Chip stone"}),
        STUMP(ToolSwap.Kind.SHOVEL, null),
        SOIL(ToolSwap.Kind.SHOVEL, null);

        final ToolSwap.Kind tool;
        /**
         * Flower-menu options to look for, or null for jobs done with the "destroy" ACTION rather
         * than a menu - stumps and soil piles are removed by the destroy verb, which is a client
         * action followed by a click on the target, not an option on the object's own menu.
         */
        final String[] options;

        Job(ToolSwap.Kind tool, String[] options) {
            this.tool = tool;
            this.options = options;
        }
    }

    private final ToolSwap tools;

    private boolean chopTrees = false;
    private boolean chopBushes = false;
    private boolean chipRocks = false;
    private boolean clearStumps = false;
    private boolean clearSoil = false;
    private boolean dropStones = true;

    /** Where the bot was started. Work stays within {@link NBotConfig#radius} of it. */
    private Coord2d origin;

    /** Targets we've given up on this run, so one odd object can't stall the shift. */
    private final Set<Long> retired = new HashSet<>();
    /** Tool kinds we've already failed to find, so we report each one once and move on. */
    private final Set<ToolSwap.Kind> missingTools = new HashSet<>();

    public NCleanupBot(GameUI gui) {
        super(gui, "Cleanup (crew)", "nCleanupBotWindow", LOG, UI.scale(230, 136));
        this.tools = new ToolSwap(gui, nav, LOG);

        add(box("Trees", () -> chopTrees, v -> chopTrees = v), UI.scale(10, 22));
        add(box("Bushes", () -> chopBushes, v -> chopBushes = v), UI.scale(10, 42));
        add(box("Rocks", () -> chipRocks, v -> chipRocks = v), UI.scale(10, 62));
        add(box("Stumps", () -> clearStumps, v -> clearStumps = v), UI.scale(120, 22));
        add(box("Soil piles", () -> clearSoil, v -> clearSoil = v), UI.scale(120, 42));
        add(box("Drop stones", () -> dropStones, v -> dropStones = v), UI.scale(120, 62));
        pack();
    }

    private interface BoolSource {
        boolean read();
    }

    private interface BoolSink {
        void write(boolean v);
    }

    /**
     * A checkbox wired straight to a field. Written as an explicit pair of tiny interfaces rather
     * than java.util.function.Supplier/Consumer so the anonymous CheckBox's own {@code set} method
     * and the accessor it delegates to can't be confused for one another.
     */
    private CheckBox box(String label, BoolSource source, BoolSink sink) {
        return new CheckBox(label) {
            {
                a = source.read();
            }

            public void set(boolean val) {
                sink.write(val);
                a = val;
            }
        };
    }

    @Override
    protected String title() {
        return "Cleanup";
    }

    @Override
    protected void onClosed() {
        if (gui.nCleanupBot == this) {
            gui.nCleanupBot = null;
            gui.nCleanupThread = null;
        }
    }

    // ------------------------------------------------------------------ the shift

    @Override
    protected void runOnce() throws InterruptedException {
        if (!(chopTrees || chopBushes || chipRocks || clearStumps || clearSoil)) {
            fatalStop = "nothing selected - tick what you want cleared.";
            return;
        }
        Gob me = gui.map.player();
        if (me == null)
            return;
        origin = me.rc;
        retired.clear();
        missingTools.clear();

        int done = 0;
        // Bounded on attempts, not successes: a target that vanishes between planning and arriving
        // returns without acting, and that must not be able to spin forever.
        int attempts = 0;

        while (running() && attempts < 2000) {
            attempts++;
            if (!checkVitals())
                return;
            if (dropStones)
                dropLoose();

            Target t = pickTarget();
            if (t == null)
                break;

            setStatus("Cleared " + done + " (" + t.job.name().toLowerCase() + ")");
            if (work(t))
                done++;
        }

        report("finished after clearing " + done + " object(s).");
        setStatus("Done: " + done + " cleared.");
    }

    private static final class Target {
        final Gob gob;
        final Job job;

        Target(Gob gob, Job job) {
            this.gob = gob;
            this.job = job;
        }
    }

    /** The nearest selected object we can both reach a slot on and hold the right tool for. */
    private Target pickTarget() throws InterruptedException {
        for (Target t : candidates()) {
            if (!running())
                throw new InterruptedException();
            if (missingTools.contains(t.job.tool))
                continue;
            if (!takeSlotAt(t.gob))
                continue;
            return t;
        }
        return null;
    }

    private List<Target> candidates() {
        List<Target> out = new ArrayList<>();
        Gob me = gui.map.player();
        if (me == null)
            return out;
        double radius = NBotConfig.radius();
        // Fetched once for the whole scan rather than per candidate: this runs over every gob in
        // view, and rebuilding the character list inside that loop would be quadratic in a forest.
        List<Gob> others = Crowd.others(gui);
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                if (retired.contains(g.id))
                    continue;
                // Anything beyond the work radius belongs to somebody else's patch. Measured from
                // where the bot was STARTED, not from where it currently is, or a run would creep
                // across the map one tree at a time.
                if (origin != null && origin.dist(g.rc) > radius)
                    continue;
                Job job = classify(g);
                if (job == null)
                    continue;
                // Someone is already working this object and has left no room. workersOn is a
                // cheap pre-filter; takeSlotAt makes the real decision.
                if (Crowd.workersOn(others, g.rc) >= WorkSlots.around(g).count)
                    continue;
                out.add(new Target(g, job));
            }
        }
        out.sort((a, b) -> Double.compare(me.rc.dist(a.gob.rc), me.rc.dist(b.gob.rc)));
        return out;
    }

    /**
     * Which job, if any, this gob is.
     *
     * The order matters: a "stump" and an "oldtrunk" both live under /trees/ but are neither
     * choppable nor the same job, so those are separated out before the standing-tree test. (The
     * stock bot's condition gets this wrong for logs and old trunks through operator precedence -
     * {@code a && !b && !c || d} - and ends up offering to chop them.)
     */
    private Job classify(Gob g) {
        String name = resname(g);
        if (name == null)
            return null;
        if (name.endsWith("/stockpile-soil"))
            return clearSoil ? Job.SOIL : null;
        if (name.endsWith("stump"))
            return clearStumps ? Job.STUMP : null;
        if (name.contains("/bumlings/"))
            return chipRocks ? Job.ROCK : null;
        if (name.contains("/bushes/"))
            return chopBushes ? Job.BUSH : null;
        if (name.contains("/trees/")) {
            if (name.endsWith("log") || name.endsWith("oldtrunk"))
                return null;  // not standing timber; leave them be
            return chopTrees ? Job.TREE : null;
        }
        return null;
    }

    // ------------------------------------------------------------------ doing the work

    /** @return true if the object was actually removed. */
    private boolean work(Target t) throws InterruptedException {
        long id = t.gob.id;
        try {
            if (!tools.equipped(t.job.tool) && !tools.equip(t.job.tool)) {
                // Retire the whole TOOL, not just this target: every other target of this job needs
                // the same one, so without it none of them can succeed and trying each in turn
                // would be a walk apiece for nothing.
                missingTools.add(t.job.tool);
                gui.error("Cleanup: no " + t.job.tool.label + " available - skipping those jobs.");
                NLog.log(LOG, "no " + t.job.tool.label + "; dropping all " + t.job + " targets");
                return false;
            }
            // Equipping moves items around and can take a moment; make sure we're still in position.
            Gob target = nav.gob(id);
            if (target == null)
                return true;  // someone else finished it while we were rummaging
            if (!inRange(target) && !nav.approach(target, BotNav.REACH))
                return false;

            clearHand();
            boolean started = (t.job.options == null)
                ? destroy(nav.gob(id))
                : rclickAndChoose(nav.gob(id), t.job.options) != null;
            if (!started) {
                retired.add(id);
                return false;
            }

            int stuck = 0;
            while (running() && nav.gob(id) != null) {
                renewSlot();
                if (!checkVitals())
                    return false;
                if (dropStones && freeSpace() == 0)
                    dropLoose();

                if (working()) {
                    stuck = 0;
                } else if (++stuck >= 8) {
                    // The action stopped with the object still standing - most often because low
                    // stamina broke it off to drink, which returns the pose to idle. Re-issue once;
                    // if that doesn't take either, this target isn't going to fall today.
                    boolean again = (t.job.options == null)
                        ? destroy(nav.gob(id))
                        : rclickAndChoose(nav.gob(id), t.job.options) != null;
                    if (!again) {
                        retired.add(id);
                        return false;
                    }
                    stuck = 0;
                }
                nav.pause(4);
            }
            return nav.gob(id) == null;
        } finally {
            releaseSlot();
        }
    }

    /**
     * Removes a stump or soil pile with the client's destroy action.
     *
     * Unlike everything else here this isn't an option on the object's own flower menu - it's the
     * destroy verb, armed first and then aimed by clicking the target, with a right-click after to
     * disarm whatever is left on the cursor.
     */
    private boolean destroy(Gob gob) throws InterruptedException {
        if (gob == null)
            return false;
        gui.act("destroy");
        gui.map.wdgmsg("click", Coord.z, gob.rc.floor(posres), 1, 0, 0, (int) gob.id,
            gob.rc.floor(posres), 0, -1);
        gui.map.wdgmsg("click", Coord.z, Coord.z, 3, 0);
        nav.pause(6);
        return true;
    }

    private boolean working() {
        if (gui.prog != null && gui.prog.prog >= 0)
            return true;
        return poseContains("pickan") || poseContains("treechop") || poseContains("chopping")
            || poseContains("shoveldig") || poseContains("dig");
    }

    // ------------------------------------------------------------------ inventory

    private int freeSpace() {
        try {
            return (gui.maininv == null) ? -1 : gui.maininv.getFreeSpace();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Throws chipped stone on the floor so a full pack can't halt the shift. */
    private void dropLoose() {
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
}
