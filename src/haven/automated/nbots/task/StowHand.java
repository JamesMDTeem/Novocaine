package haven.automated.nbots.task;

import haven.Coord;
import haven.Gob;
import haven.Inventory;
import haven.Loader;
import haven.MapView;
import haven.automated.nbots.core.BotCtx;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.core.Task;

import static haven.OCache.posres;

/**
 * Gets whatever is on the cursor off it, and makes sure it stays off.
 *
 * A held item is not a cosmetic state. Movement in this client is a map click, and a map click
 * made with something on the cursor DROPS OR USES that something instead of walking - so a bot
 * that ends a step holding a clod of soil cannot travel, cannot approach, and cannot recover,
 * because every attempt to go anywhere is spent on the item. That is the "stuck with dirt on the
 * cursor" failure exactly: not a bot that decided to stop, a bot whose every move was being
 * reinterpreted.
 *
 * <h2>A stockpilable item on the cursor has two forms</h2>
 *
 * This is the mechanic the whole class turns on, and it is easy to get backwards. Holding
 * something that can be stockpiled, a right-click does not cancel or drop anything: it TOGGLES the
 * cursor between the item form (one clod) and the stockpile form (a whole pile, ghosted where it
 * would go). Right-click again and it toggles back. Both forms are "something on the cursor" and
 * both stop the character moving; only the item form can be put back in the pack.
 *
 * So getting out is two moves in order, and either alone leaves the bot stuck:
 *
 * <ol>
 *   <li><b>Toggle back to the item form</b>, by sending what a right-click sends -
 *       {@code MapView.mousedown} turns any button pressed during a placement into
 *       {@code wdgmsg("place", rc, angle, button, modflags)}, so button 3 is the one. Note the
 *       item comes BACK to the cursor here rather than going away; this step makes the problem
 *       addressable, it does not solve it. It is emphatically not {@code gk 27} - that is the
 *       container-close key, and sending it at a placement (which {@link MakePile} used to do) is
 *       simply a message the placement ignores, which is why the soil never came back.</li>
 *   <li><b>Then put the item away</b>, into the pack if there is a cell for it and on the ground
 *       if there is not. The pack normally has the cell the item came out of, but this runs on the
 *       path where the pack is full, so the ground is the fallback rather than the failure: one
 *       clod on the floor inside the bot's own area is a far smaller problem than a bot that
 *       cannot move, and the next trip through picks it up.</li>
 * </ol>
 *
 * A third thing has to be right or both steps read as already-done: <b>wait for the hand to settle
 * before believing it</b>. {@code GameUI.vhand} is the drag WIDGET, rebuilt from
 * {@code GameUI.hand} on a UI pass, so a pickup the server has already acknowledged reads as an
 * empty cursor for a frame or two. Checking it immediately after asking for something is how an
 * item ends up held by a bot that has just satisfied itself the hand is empty.
 *
 * Cheap enough to call defensively - an empty cursor costs one field read - which is the point:
 * a bot runs this at the top of each cycle and can no longer inherit a wedged hand from anything
 * that went wrong in the last one.
 */
public class StowHand implements Task {
    /** Polls to wait for the server to take a placement ghost down. */
    private static final int UNPLACE_TICKS = 40;
    /**
     * Polls to wait for a pickup already in flight to land.
     *
     * Deliberately short and deliberately not zero. The whole point is to catch the item that is
     * ON ITS WAY to the cursor, since that is the one a naive check misses; but an empty cursor
     * is also the normal case, so waiting long for it would tax every cycle.
     */
    private static final int SETTLE_TICKS = 12;
    /** Polls to wait for one put-away to take effect. */
    private static final int MOVE_TICKS = 24;
    /** How many times to try before leaving it and saying so. */
    private static final int ATTEMPTS = 3;

    @Override
    public Outcome run(BotCtx ctx) throws InterruptedException {
        /* Toggle out of stockpile form FIRST, unconditionally - before asking whether anything is
         * held at all. In that form the cursor may well read as empty while the ghost is what is
         * actually holding the character in place, so an early "nothing held, nothing to do" would
         * return from precisely the state this exists to get out of. */
        unplace(ctx);
        // Give anything in flight - including the item the toggle just handed back - a moment to
        // arrive, so "nothing held" means it.
        ctx.nav.waitUntil(() -> held(ctx), SETTLE_TICKS);
        if (!held(ctx))
            return Outcome.ok();

        for (int i = 0; (i < ATTEMPTS) && held(ctx); i++) {
            if (!ctx.running())
                throw new InterruptedException();
            if (toPack(ctx))
                continue;
            toGround(ctx);
        }
        if (!held(ctx))
            return Outcome.ok();
        /* Blocked rather than failed: whatever is stuck to the cursor is stuck for a reason that
         * may pass - a full pack and no standable ground under us is the obvious one - and the
         * caller re-running this next cycle is a better answer than ending the shift. */
        return Outcome.blocked("couldn't get " + describe(ctx) + " off the cursor");
    }

    /**
     * Whether anything is on the cursor.
     *
     * Both sources, OR-ed. {@code hand} is what the server said and {@code vhand} is what has been
     * drawn from it, and they disagree for a frame in both directions - so treating either as
     * enough on its own is how a held item reads as none.
     */
    public static boolean held(BotCtx ctx) {
        try {
            return !ctx.gui.hand.isEmpty() || (ctx.gui.vhand != null);
        } catch (RuntimeException e) {
            // The collection being rebuilt under us. Assume something is there and look again.
            return true;
        }
    }

    /**
     * Toggles a stockpile-form cursor back to its item form, if it is in one.
     *
     * Leaves the item ON the cursor - that is what the toggle does, and it is why this is only
     * ever a first step. See the class comment.
     */
    private static void unplace(BotCtx ctx) throws InterruptedException {
        if (!armed(ctx))
            return;
        Gob me = ctx.player();
        Coord at = (me == null) ? Coord.z : me.rc.floor(posres);
        // Button 3, spelled the way MapView.mousedown spells it - the only thing in this client
        // that has ever sent a "place" the server acts on.
        ctx.gui.map.wdgmsg("place", at, 0, 3, 0);
        ctx.nav.waitUntil(() -> !armed(ctx), UNPLACE_TICKS);
    }

    /** Whether the server has a placement ghost up for us. */
    public static boolean armed(BotCtx ctx) {
        MapView mv = ctx.gui.map;
        if (mv == null)
            return false;
        Loader.Future<MapView.Plob> p = mv.placing();
        return (p != null) && p.done();
    }

    /** Puts it back in the pack. False if there is nowhere for it to go. */
    private static boolean toPack(BotCtx ctx) throws InterruptedException {
        Inventory inv = ctx.gui.maininv;
        if (inv == null)
            return false;
        Coord cell;
        try {
            cell = inv.isRoom(1, 1);
        } catch (RuntimeException e) {
            return false;
        }
        if (cell == null)
            return false;
        inv.wdgmsg("drop", cell);
        ctx.nav.waitUntil(() -> !held(ctx), MOVE_TICKS);
        return !held(ctx);
    }

    /** Drops it where we stand. The fallback, not the failure - see the class comment. */
    private static void toGround(BotCtx ctx) throws InterruptedException {
        Gob me = ctx.player();
        if (me == null)
            return;
        ctx.gui.map.wdgmsg("drop", Coord.z, me.rc.floor(posres), 0);
        ctx.nav.waitUntil(() -> !held(ctx), MOVE_TICKS);
    }

    private static String describe(BotCtx ctx) {
        try {
            if (ctx.gui.vhand != null)
                return ctx.gui.vhand.item.getname();
        } catch (RuntimeException e) {
        }
        return "what it is holding";
    }

    @Override
    public String label() {
        return "stow hand";
    }
}
