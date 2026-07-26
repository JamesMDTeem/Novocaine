package haven.automated.nbots.task;

import haven.Coord;
import haven.FlowerMenu;
import haven.Gob;
import haven.Loading;
import haven.Resource;
import haven.Widget;
import haven.automated.nbots.core.BotCtx;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.core.Task;

import static haven.OCache.posres;

/**
 * Take a spot around an object, do a thing to it, and keep at it until the object is gone.
 *
 * This is the task the whole restructuring was for. Chipping a boulder, chopping a tree, clearing a
 * stump and digging out a soil pile are the same eighty lines with a different verb, and before
 * this existed they were written twice - once in the cellar digger, once in the cleanup bot - with
 * the third copy due the moment a fourth bot appeared. The differences that are real are the
 * parameters below; everything else is shared.
 *
 * What it handles that a naive loop doesn't:
 *
 * - The action is chosen from the LIVE flower menu BY NAME. Picking index 0 blind (as the stock
 *   bots do) works right up until an object offers something else first, and on a menu containing
 *   "Destroy" that is not a harmless mistake. An unrecognised menu costs one skipped target and is
 *   logged with what it actually offered, which is exactly what filling the gap needs.
 * - Some jobs aren't menu options at all. Stumps and soil piles go through the client's destroy
 *   VERB - armed, then aimed by clicking the target - so that is a mode rather than another bot.
 * - The slot reservation is renewed while the work runs, so a long chop doesn't quietly expire and
 *   let a second bot walk into us.
 * - Stalling is expected, not exceptional. Low stamina breaks the action off to drink, which
 *   returns the pose to idle; that is not "finished", so the action is re-issued once before the
 *   target is given up on.
 */
public class WorkGob implements Task {
    /** How the action is started. */
    public enum Mode {
        /** Right-click the object and choose one of {@link #options} from its own menu. */
        MENU,
        /**
         * Arm the client's "destroy" action and click the object. Not a menu option - it is a verb
         * aimed at a target - which is why stumps and soil piles need their own mode.
         */
        DESTROY
    }

    private final long id;
    private final Mode mode;
    private final String[] options;
    private final TakeWorkSlot slot;
    private final Runnable perTick;

    /** How many idle polls with the object still standing before the action is re-issued. */
    private static final int STALL_LIMIT = 8;
    /** How many re-issues before the target is treated as one we can't finish. */
    private static final int REISSUE_LIMIT = 2;

    /**
     * @param slot    the reservation to keep alive, already held. Released by the caller.
     * @param perTick optional hook run on every poll - used for "drop stone when the pack fills",
     *                which has to happen DURING the work rather than between targets.
     */
    public WorkGob(Gob target, Mode mode, String[] options, TakeWorkSlot slot, Runnable perTick) {
        this.id = (target == null) ? -1 : target.id;
        this.mode = mode;
        this.options = options;
        this.slot = slot;
        this.perTick = perTick;
    }

    public static WorkGob menu(Gob target, TakeWorkSlot slot, String... options) {
        return new WorkGob(target, Mode.MENU, options, slot, null);
    }

    public static WorkGob destroy(Gob target, TakeWorkSlot slot) {
        return new WorkGob(target, Mode.DESTROY, null, slot, null);
    }

    /** Copy with a per-poll hook attached. Keyed by id, since the gob may since have moved. */
    private WorkGob(long id, Mode mode, String[] options, TakeWorkSlot slot, Runnable perTick) {
        this.id = id;
        this.mode = mode;
        this.options = options;
        this.slot = slot;
        this.perTick = perTick;
    }

    public WorkGob withTick(Runnable hook) {
        return new WorkGob(id, mode, options, slot, hook);
    }

    @Override
    public Outcome run(BotCtx ctx) throws InterruptedException {
        Gob target = ctx.gob(id);
        if (target == null)
            return Outcome.ok();  // somebody else finished it; that is still the job done

        if (!Approach.inRange(ctx, target)) {
            Outcome o = new Approach(target).run(ctx);
            if (!o.isOk())
                return o;
        }

        /* Asking while too tired just gets refused - "You are too tired to chop" - and a refusal
         * looks exactly like a stall, so the old code sat through the full stall count three times
         * over before anything noticed it needed a drink. Checking first turns three wasted
         * attempts into an immediate hand-off. Deliberately the SAME threshold Upkeep drinks at:
         * a stricter one here would report blocked and then have upkeep decide there was nothing
         * to do, which is a bot that stops and never recovers. */
        if (ctx.stamina() < Upkeep.DRINK_BELOW)
            return Outcome.blocked("too tired to work");

        clearHand(ctx);
        Outcome started = start(ctx);
        if (!started.isOk())
            return started;

        int stalled = 0;
        int reissues = 0;
        while (ctx.running() && ctx.gob(id) != null) {
            if (ctx.stamina() < Upkeep.DRINK_BELOW)
                return Outcome.blocked("too tired to carry on");
            if (slot != null)
                slot.renew();
            if (perTick != null)
                perTick.run();

            if (working(ctx)) {
                stalled = 0;
            } else if (++stalled >= STALL_LIMIT) {
                if (++reissues > REISSUE_LIMIT)
                    return Outcome.failed("the action keeps stopping with the object still there");
                Outcome again = start(ctx);
                if (!again.isOk())
                    return again;
                stalled = 0;
            }
            ctx.nav.pause(4);
        }
        return ctx.gob(id) == null ? Outcome.ok() : Outcome.blocked("stopped before it was finished");
    }

    private Outcome start(BotCtx ctx) throws InterruptedException {
        Gob target = ctx.gob(id);
        if (target == null)
            return Outcome.ok();
        if (mode == Mode.DESTROY) {
            ctx.gui.act("destroy");
            ctx.gui.map.wdgmsg("click", Coord.z, target.rc.floor(posres), 1, 0, 0, (int) target.id,
                target.rc.floor(posres), 0, -1);
            // Disarm whatever is left on the cursor, or the next click does something unintended.
            ctx.gui.map.wdgmsg("click", Coord.z, Coord.z, 3, 0);
            ctx.nav.pause(6);
            return Outcome.ok();
        }
        return chooseFromMenu(ctx, target);
    }

    /**
     * Right-clicks and picks the first offered option we asked for.
     *
     * A menu that doesn't open is BLOCKED rather than failed: the click can land a frame before the
     * character finishes arriving, which is transient. A menu that opens and offers nothing we know
     * is FAILED, because asking it again will get the same answer - and what it did offer is logged,
     * since that string is what teaching the bot about this object needs.
     */
    private Outcome chooseFromMenu(BotCtx ctx, Gob target) throws InterruptedException {
        ctx.gui.map.wdgmsg("click", Coord.z, target.rc.floor(posres), 3, 0, 0, (int) target.id,
            target.rc.floor(posres), 0, -1);
        FlowerMenu fm = awaitMenu(ctx);
        if (fm == null)
            return Outcome.blocked("the object's menu didn't open");

        for (String opt : options) {
            for (FlowerMenu.Petal petal : fm.opts) {
                if (opt.equals(petal.name)) {
                    fm.wdgmsg("cl", petal.num, 0);
                    ctx.nav.waitUntil(() -> liveMenu(ctx) == null, 50);
                    return Outcome.ok();
                }
            }
        }
        ctx.log("no wanted option on " + resname(target) + " " + java.util.Arrays.toString(options)
            + " - menu offers " + petals(fm));
        fm.wdgmsg("cl", -1);
        ctx.nav.waitUntil(() -> liveMenu(ctx) == null, 50);
        return Outcome.failed("nothing we recognise on its menu");
    }

    /** The character is mid-action: a work pose, or a progress bar. */
    private boolean working(BotCtx ctx) {
        if (ctx.onProgress())
            return true;
        return ctx.poseContains("pickan") || ctx.poseContains("treechop")
            || ctx.poseContains("chopping") || ctx.poseContains("shoveldig")
            || ctx.poseContains("dig") || ctx.poseContains("sawing");
    }

    private static void clearHand(BotCtx ctx) throws InterruptedException {
        if (ctx.gui.vhand == null)
            return;
        Gob p = ctx.player();
        if (p != null)
            ctx.gui.map.wdgmsg("drop", Coord.z, p.rc.floor(posres), 0);
        ctx.nav.waitUntil(() -> ctx.gui.vhand == null, 20);
    }

    private static FlowerMenu awaitMenu(BotCtx ctx) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            if (!ctx.running())
                throw new InterruptedException();
            FlowerMenu fm = liveMenu(ctx);
            if (fm != null)
                return fm;
            Thread.sleep(25);
        }
        return null;
    }

    private static FlowerMenu liveMenu(BotCtx ctx) {
        return findChild(ctx.gui.ui.root, FlowerMenu.class);
    }

    private static <T extends Widget> T findChild(Widget root, Class<T> cls) {
        for (Widget w = root.child; w != null; w = w.next) {
            if (cls.isInstance(w))
                return cls.cast(w);
            T deep = findChild(w, cls);
            if (deep != null)
                return deep;
        }
        return null;
    }

    private static String petals(FlowerMenu fm) {
        StringBuilder sb = new StringBuilder("[");
        for (FlowerMenu.Petal p : fm.opts) {
            if (sb.length() > 1)
                sb.append(", ");
            sb.append(p.name);
        }
        return sb.append(']').toString();
    }

    static String resname(Gob gob) {
        if (gob == null)
            return null;
        try {
            Resource res = gob.getres();
            return (res == null) ? null : res.name;
        } catch (Loading l) {
            return null;
        }
    }

    @Override
    public String label() {
        return (mode == Mode.DESTROY) ? "destroy" : "work";
    }
}
