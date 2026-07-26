package haven.automated.nbots;

import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.Loading;
import haven.Resource;
import haven.UI;
import haven.automated.nbots.core.Alias;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.task.Deposit;
import haven.automated.nbots.task.TakeWorkSlot;
import haven.automated.nbots.task.ToolSwap;
import haven.automated.nbots.task.TravelTo;
import haven.automated.nbots.task.WorkGob;
import haven.automated.nbots.world.Crowd;
import haven.automated.nbots.world.Place;
import haven.automated.nbots.world.PlaceRoles;
import haven.automated.nbots.world.Places;
import haven.automated.nbots.world.WorkSlots;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Clearing a site: trees, bushes, boulders, stumps and soil piles.
 *
 * The interesting part of this bot is the {@link Job} table. Its five checkboxes need three
 * different two-handed tools between them, so the tool is a property of the JOB rather than
 * something the player is expected to have equipped - which is why the stock version silently
 * accomplishes nothing on any job that doesn't match whatever was already in hand. Beyond that
 * table there is almost nothing here: taking a spot, approaching, choosing a menu option by name,
 * keeping at it, drinking, eating and coming back are all shared tasks.
 *
 * That is the point of the restructuring, and it shows up as a number: the work loop this bot and
 * the cellar digger each used to carry is now one {@link WorkGob}, and what is left of each bot is
 * its own subject matter.
 *
 * Where output goes is a {@link Places} question rather than a setting - the bot has stone, and
 * asks where stone goes. If nowhere is tagged, it drops it, because a cleanup crew that stops
 * working for want of somewhere to file its rubbish is worse than one that leaves a pile.
 *
 * The stock Cleanup Bot is untouched and still on the Bots tab.
 */
public class NCleanupBot extends NBot {
    private static final String LOG = "nbot-cleanup.log";

    /** What a target is, which decides both the tool and how the action is started. */
    private enum Job {
        TREE("trees", ToolSwap.Kind.AXE, new String[] {"Chop"}),
        BUSH("bushes", ToolSwap.Kind.AXE, new String[] {"Chop", "Destroy"}),
        ROCK("rocks", ToolSwap.Kind.PICK, new String[] {"Chip", "Mine", "Chip stone"}),
        /**
         * Stumps and soil piles are removed with the client's destroy VERB, not an option on the
         * object's own menu - hence the null options and {@link WorkGob.Mode#DESTROY}.
         */
        STUMP("stumps", ToolSwap.Kind.SHOVEL, null),
        SOIL("soil", ToolSwap.Kind.SHOVEL, null);

        final String setting;
        final ToolSwap.Kind tool;
        final String[] options;

        Job(String setting, ToolSwap.Kind tool, String[] options) {
            this.setting = setting;
            this.tool = tool;
            this.options = options;
        }
    }

    /** What the bot throws away rather than carries home. */
    private static final Alias SPOIL = new Alias("spoil", "Stone", "Boulder");

    /**
     * How the bot decides what counts as its patch.
     *
     * The two answers are genuinely different jobs rather than two tunings of one. Nearest-first is
     * "clear around me", which is what you want when you have walked somewhere and started a bot;
     * it is bounded by a radius from where the shift began so that a run cannot creep across the
     * map one tree at a time, each new target legitimising the next. Area mode is "clear THAT", and
     * it finishes: the bot walks to the area first and stops when nothing inside it is left, which
     * is both how a crew splits a site between them and how you get a bot that ends by itself.
     */
    private static final String[] MODES = {"Nearest first", "Inside a work area"};
    private static final int MODE_NEAREST = 0, MODE_AREA = 1;

    /** Where the shift started, when no work place is defined. */
    private Coord2d origin;
    private Place workPlace;

    /** Targets given up on this shift, so one odd object can't stall it. */
    private final Set<Long> retired = new HashSet<>();
    /** Tools we've already failed to find, so each is reported once and its jobs dropped. */
    private final Set<ToolSwap.Kind> missingTools = new HashSet<>();

    public NCleanupBot(GameUI gui) {
        super(gui, "NCleanupBot", "Cleanup (crew)", LOG, UI.scale(300, 200));
        settings.flag("trees", "Trees", false);
        settings.flag("bushes", "Bushes", false);
        settings.flag("rocks", "Rocks", false);
        settings.flag("stumps", "Stumps", false);
        settings.flag("soil", "Soil piles", false);
        settings.flag("dropspoil", "Get rid of stone", true);
        settings.number("radius", "Radius", 300);
        settings.choice("mode", "Where to work", MODE_NEAREST, MODES);
        settings.layout(this, UI.scale(10, 22), 2, UI.scale(140));
        pack();
    }

    @Override
    protected String title() {
        return "Cleanup";
    }

    // ------------------------------------------------------------------ the shift

    @Override
    protected Outcome work() throws InterruptedException {
        if (!settings.anyOn("trees", "bushes", "rocks", "stumps", "soil"))
            return Outcome.failed("nothing selected - tick what you want cleared");
        Gob me = ctx.player();
        if (me == null)
            return Outcome.blocked("no character");

        origin = me.rc;
        retired.clear();
        missingTools.clear();

        workPlace = null;
        if (settings.picked("mode", MODE_AREA)) {
            /* The one we are standing in first, so a crew can be posted to adjacent areas and each
             * bot takes the one it was left in; otherwise the nearest, and walk to it. Failing
             * outright when none is defined is right here in a way it would not be for water: the
             * player explicitly asked for area mode, so silently clearing a radius instead would be
             * doing a different job from the one they chose. */
            workPlace = Places.containing(gui, PlaceRoles.WORK);
            if (workPlace == null)
                workPlace = Places.nearest(gui, PlaceRoles.WORK);
            if (workPlace == null)
                return Outcome.failed("no place is tagged for work - draw one in Bot Places, "
                    + "or switch this bot back to Nearest first");
            ctx.log("area mode: working \"" + workPlace.name + "\" ("
                + workPlace.w + "x" + workPlace.h + " tiles)");
            Outcome t = new TravelTo(workPlace).run(ctx);
            if (!t.isOk())
                return t;
        } else {
            ctx.log("nearest-first mode: radius " + settings.num("radius")
                + "u from where the shift started");
        }

        int done = 0;
        // Bounded on ATTEMPTS, not successes: a target that vanishes between planning and arriving
        // returns without acting, and that must not be able to spin forever.
        int attempts = 0;

        while (running() && attempts < 2000) {
            attempts++;
            if (!upkeep())
                return Outcome.failed(fatalStop);
            if (settings.on("dropspoil"))
                new Deposit(SPOIL, true).run(ctx);

            Target t = takeTarget();
            if (t == null) {
                /* "Nothing left" and "we are not there any more" look identical from here, because
                 * the scan only sees LOADED gobs and upkeep may just have walked us to a barrel
                 * three hundred tiles away. In area mode that is the difference between a bot that
                 * finishes its patch and one that declares victory after its first drink, so go
                 * back and look again before believing it. Terminates because the trip ends inside
                 * the area, and the second pass takes this branch no further. */
                Gob here = ctx.player();
                if (workPlace != null && here != null && !workPlace.contains(gui, here.rc)) {
                    ctx.log("no targets in sight; heading back to \"" + workPlace.name + "\"");
                    if (new TravelTo(workPlace).run(ctx).isOk())
                        continue;
                }
                break;
            }

            setStatus("Cleared " + done + " (" + t.job.name().toLowerCase() + ")");
            try {
                Outcome o = clear(t);
                if (o.isOk())
                    done++;
                else if (o.isFailed())
                    retired.add(t.gob.id);
            } finally {
                t.slot.release();
            }
        }

        report("finished after clearing " + done + " object(s).");
        setStatus("Done: " + done + " cleared.");
        return Outcome.ok();
    }

    private Outcome clear(Target t) throws InterruptedException {
        Outcome tool = tools.require(t.job.tool);
        if (!tool.isOk()) {
            // Retire the whole TOOL, not just this target: every target of this job needs the same
            // one, so without it none can succeed and trying each in turn is a walk apiece for
            // nothing.
            missingTools.add(t.job.tool);
            gui.error("Cleanup: no " + t.job.tool.label + " available - skipping those jobs.");
            ctx.log("no " + t.job.tool.label + "; dropping all " + t.job + " targets");
            return Outcome.blocked("no " + t.job.tool.label);
        }
        WorkGob action = (t.job.options == null)
            ? WorkGob.destroy(t.gob, t.slot)
            : WorkGob.menu(t.gob, t.slot, t.job.options);
        return action.run(ctx);
    }

    // ------------------------------------------------------------------ choosing targets

    private static final class Target {
        final Gob gob;
        final Job job;
        final TakeWorkSlot slot;

        Target(Gob gob, Job job, TakeWorkSlot slot) {
            this.gob = gob;
            this.job = job;
            this.slot = slot;
        }
    }

    /** The nearest selected object we can get a slot on, already stood in. */
    private Target takeTarget() throws InterruptedException {
        for (Candidate c : candidates()) {
            if (!running())
                throw new InterruptedException();
            if (missingTools.contains(c.job.tool))
                continue;
            TakeWorkSlot slot = new TakeWorkSlot(c.gob);
            if (slot.run(ctx).isOk())
                return new Target(c.gob, c.job, slot);
            slot.release();
        }
        return null;
    }

    private static final class Candidate {
        final Gob gob;
        final Job job;

        Candidate(Gob gob, Job job) {
            this.gob = gob;
            this.job = job;
        }
    }

    private List<Candidate> candidates() {
        List<Candidate> out = new ArrayList<>();
        Gob me = ctx.player();
        if (me == null)
            return out;
        double radius = settings.num("radius");
        // Fetched once for the whole scan rather than per candidate: this runs over every gob in
        // view, and rebuilding the character list inside that loop would be quadratic in a forest.
        List<Gob> others = Crowd.others(gui);
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc) {
                if (retired.contains(g.id) || !inBounds(g))
                    continue;
                Job job = classify(g);
                if (job == null)
                    continue;
                // Already fully surrounded. A cheap pre-filter; TakeWorkSlot makes the real call.
                if (Crowd.workersOn(others, g.rc) >= WorkSlots.around(g).count)
                    continue;
                out.add(new Candidate(g, job));
            }
        }
        out.sort((a, b) -> Double.compare(me.rc.dist(a.gob.rc), me.rc.dist(b.gob.rc)));
        return out;
    }

    /**
     * Whether a target is inside the patch this bot was sent to.
     *
     * Which of the two answers applies is decided once, at the top of the shift, by the mode - so
     * a work place lying around from another bot cannot quietly shrink a nearest-first run, and a
     * radius cannot quietly widen an area run. The radius is measured from where the shift STARTED
     * rather than from where the character currently is, or a run would creep across the map one
     * tree at a time, each new target legitimising the next.
     */
    private boolean inBounds(Gob g) {
        if (workPlace != null)
            return workPlace.contains(gui, g.rc);
        return origin == null || origin.dist(g.rc) <= settings.num("radius");
    }

    /**
     * Which job, if any, this gob is.
     *
     * The order matters: a stump and an old trunk both live under /trees/ but are neither
     * choppable nor the same job, so those are separated out before the standing-tree test. (The
     * stock bot's condition gets this wrong through operator precedence - {@code a && !b && !c || d}
     * - and ends up offering to chop logs and old trunks.)
     */
    private Job classify(Gob g) {
        String name = resname(g);
        if (name == null)
            return null;
        if (name.endsWith("/stockpile-soil"))
            return enabled(Job.SOIL);
        if (name.endsWith("stump"))
            return enabled(Job.STUMP);
        if (name.contains("/bumlings/"))
            return enabled(Job.ROCK);
        if (name.contains("/bushes/"))
            return enabled(Job.BUSH);
        if (name.contains("/trees/")) {
            if (name.endsWith("log") || name.endsWith("oldtrunk"))
                return null;  // not standing timber; leave them be
            return enabled(Job.TREE);
        }
        return null;
    }

    private Job enabled(Job job) {
        return settings.on(job.setting) ? job : null;
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
