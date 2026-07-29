package haven.automated.nbots.task;

import haven.Speedget;
import haven.automated.nbots.core.BotCtx;
import haven.automated.nbots.core.NBotConfig;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.core.Task;
import haven.automated.nbots.core.Widgets;
import haven.automated.nbots.world.WorldAnchor;

/**
 * Keeps the character able to keep working, and puts them back where they were.
 *
 * The bookmark-restore-return shape is the important part, and it is the thing that makes a bot
 * able to run unattended for hours rather than minutes. Without the return leg a bot that ran dry
 * would refill and then carry on working from wherever the water was, which for a crew means all
 * of them slowly migrating to the barrels and clearing a different patch of forest from the one
 * they were sent to.
 *
 * The bookmark is a {@link WorldAnchor}, not a raw coordinate, because by construction the trip is
 * long enough that the work site may not be rendered when the bot turns round - which is exactly
 * the case a raw coordinate cannot survive.
 *
 * Only travels if it actually has to: a bot that can drink from its own waterskin never leaves,
 * and this returns immediately when nothing is wrong, so it is cheap enough to call every cycle.
 */
public class Upkeep implements Task {
    /** Below this fraction of stamina, break off and drink. */
    public static final double DRINK_BELOW = 0.40;
    /**
     * The speed we want to keep: {@code Speedget}'s four are crawl, walk, run, sprint.
     *
     * Kept by watching the game rather than by guessing a stamina figure. The server decides when
     * sprinting stops being allowed and says so by lowering the speed widget's MAX - so the moment
     * the cap drops is observable exactly, and no threshold has to be assumed. Which is just as
     * well: the threshold is the server's and nothing in this client states it, so a number written
     * here would be folklore, and would go stale the first time it was rebalanced.
     *
     * Worth keeping rather than tolerating, because it is not a small loss. Every journey a bot
     * makes is longer at a lower speed, so a character that slips a gear stays slipped - it spends
     * more of the shift walking, which costs more stamina, which keeps the cap down.
     */
    private static final int WANT_SPEED = 3;
    /** Below this fraction of energy, go and eat. */
    public static final double EAT_BELOW = 0.35;
    /** Below this fraction of health, nothing is worth continuing for. */
    public static final double PANIC_HEALTH = 0.02;

    @Override
    public Outcome run(BotCtx ctx) throws InterruptedException {
        if (ctx.health() < PANIC_HEALTH) {
            ctx.log("health critical - hearthing home");
            ctx.gui.act("travel", "hearth");
            Thread.sleep(8000);
            return Outcome.failed("health was critical");
        }

        boolean needDrink = (ctx.stamina() < DRINK_BELOW) || slowed(ctx);
        boolean needEat = ctx.energy() < EAT_BELOW && NBotConfig.on(NBotConfig.Key.autoEat);
        if (!needDrink && !needEat) {
            // Energy is low but eating is switched off - that is a stop, not something to travel
            // for, and saying so is more useful than silently working until the character faints.
            if (ctx.energy() < EAT_BELOW)
                return Outcome.failed("energy too low and auto-eat is off (eat something)");
            return Outcome.ok();
        }

        // Bookmarked BEFORE anything moves. Null means the map file doesn't yet know where we are,
        // which is recoverable - we can still go and eat, we just can't promise to come back, and
        // saying so afterwards is better than refusing to eat at all.
        WorldAnchor home = WorldAnchor.capturePlayer(ctx.gui);

        if (needDrink) {
            Outcome o = new Drink().run(ctx);
            // Put the gear back before reporting anything. The cap lifting does not re-select the
            // speed on its own, so a character that has just drunk is allowed to sprint and still
            // walking - which is the state this whole check exists to get out of.
            resume(ctx);
            if (!o.isOk())
                return o;
        }
        if (needEat) {
            Outcome o = new Eat().run(ctx);
            if (!o.isOk())
                return o;
        }

        if (home == null)
            return Outcome.blocked("restored, but couldn't tell where to go back to");
        Outcome back = new TravelTo(home).run(ctx);
        if (!back.isOk()) {
            ctx.log("restored but couldn't get back to " + home);
            return Outcome.blocked("restored, but couldn't get back to the work site");
        }
        return Outcome.ok();
    }

    /**
     * True if the game has taken our top speed away, and putting the character back up to it is
     * the selected speed's job as well as drinking's.
     *
     * Both halves are needed. MAX below what we want means the server will not allow it; CUR below
     * what we want means it is allowed and we are simply not using it, which happens because the
     * client drops the selection when the cap falls and does not put it back when the cap returns.
     */
    private static boolean slowed(BotCtx ctx) {
        Speedget s = speed(ctx);
        return (s != null) && ((s.max < WANT_SPEED) || (s.cur < WANT_SPEED));
    }

    /** Puts the selected speed back up to whatever is now allowed. Free when nothing changed. */
    static void resume(BotCtx ctx) {
        Speedget s = speed(ctx);
        if ((s != null) && (s.cur < Math.min(WANT_SPEED, s.max)))
            s.set(Math.min(WANT_SPEED, s.max));
    }

    private static Speedget speed(BotCtx ctx) {
        try {
            return Widgets.find(ctx.gui.ui.root, Speedget.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public String label() {
        return "upkeep";
    }
}
