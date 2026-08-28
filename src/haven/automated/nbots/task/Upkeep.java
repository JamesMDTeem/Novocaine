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
     * The speed every crew bot travels at. {@code Speedget}'s four are crawl, walk, run, sprint,
     * indexed from zero - so this is RUN, and deliberately not sprint.
     *
     * Sprinting is not a faster version of running, it is a different trade: it burns stamina the
     * whole time it is on, and a bot's stamina is not free - it is a walk to a water place and back
     * every time it runs out. A crew that sprints everywhere spends a visible fraction of its shift
     * fetching water to pay for the sprinting, and arrives at each job with less in hand for the
     * job itself. Running costs nothing to hold and is quick enough for a bot that is never in a
     * hurry about anything.
     *
     * Kept by watching the game rather than by guessing a stamina figure. The server decides when
     * a speed stops being allowed and says so by lowering the speed widget's MAX - so the moment
     * the cap drops is observable exactly, and no threshold has to be assumed. Which is just as
     * well: the threshold is the server's and nothing in this client states it, so a number written
     * here would be folklore, and would go stale the first time it was rebalanced.
     *
     * Worth keeping rather than tolerating, because it is not a small loss. Every journey a bot
     * makes is longer at a lower speed, so a character that slips a gear stays slipped - it spends
     * more of the shift walking, which costs more stamina, which keeps the cap down.
     *
     * This one constant is the whole of it: {@link #resume} is the only thing in the nbots tree
     * that sets a speed, and every bot reaches it either through the shared upkeep step or by
     * calling it directly.
     */
    private static final int WANT_SPEED = 2;
    /**
     * Below this fraction of energy, go and eat.
     *
     * 0.25 rather than 0.35 because stopping a shift at 3500% threw away a third of the
     * working range for no reason - the legacy single-character bots have always used 0.25
     * (see CellarDiggingBot, CleanupBot, FishingBot) and nothing goes wrong down there.
     */
    public static final double EAT_BELOW = 0.25;
    /** Below this fraction of health, nothing is worth continuing for. */
    public static final double PANIC_HEALTH = 0.02;

    @Override
    public Outcome run(BotCtx ctx) throws InterruptedException {
        if (ctx.health() < PANIC_HEALTH) {
            ctx.log("health critical - hearthing home");
            haven.automated.helpers.HearthTravel.travel(ctx.gui);
            return Outcome.failed("health was critical");
        }

        /* Put the gear back FIRST, every cycle, whatever else is or is not wrong.
         *
         * This used to happen only inside the drink branch, and the trigger for that branch was
         * `slowed` - which is true whenever the SELECTED speed is below what we want, for any
         * reason at all. The client drops the selection whenever the cap falls and does not put it
         * back when the cap returns, so an ordinary dip in stamina left the character walking
         * afterwards; and the only thing that would put it right was a full drink-and-return-home
         * errand. A bot at full stamina that had merely lost its gear went for a drink, and, when
         * the waterskin's contents did not happen to be readable, walked to a barrel for it.
         *
         * Which is the wrong shape twice over. Re-selecting a speed is free, needs no reason, and
         * has nothing to do with being thirsty - so do it unconditionally and stop making it an
         * errand. What is left for the drink test is the honest signal: the server LOWERING THE
         * CAP, which it does when the character is too tired to run, and which no threshold in this
         * client has to guess at. */
        resume(ctx);
        boolean needDrink = (ctx.stamina() < DRINK_BELOW) || capped(ctx);
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
     * True if the GAME has taken our top speed away - the only half of this that means "tired".
     *
     * MAX below what we want is the server refusing to let the character run, which it does on
     * stamina and which is therefore a fact about stamina that no threshold here has to guess at.
     * CUR below what we want used to be lumped in with it and must not be: that is only the client
     * having dropped the selection, which {@link #resume} puts back for nothing, and treating it as
     * thirst sent a bot at full stamina off to a barrel.
     *
     * Note this fires LATER than it used to, and should. It is measured against
     * {@link #WANT_SPEED}, which is now run rather than sprint - and the server withdraws sprint a
     * long way above the point at which it withdraws running. Asking "can I still sprint?" was
     * asking a question about a gear the bots no longer use, and answering it with a trip to a
     * barrel. {@link #DRINK_BELOW} remains the primary trigger either way.
     */
    private static boolean capped(BotCtx ctx) {
        Speedget s = speed(ctx);
        return (s != null) && (s.max < WANT_SPEED);
    }

    /**
     * Puts the selected speed back up to whatever is now allowed. Free when nothing changed.
     *
     * Public because a bot that does not run the full upkeep step still needs it: the client
     * drops the speed selection on its own, and without this the character walks the rest of the
     * shift at whatever gear it happened to land in. NBeeSmokerBot is the case in point.
     */
    public static void resume(BotCtx ctx) {
        Speedget s = speed(ctx);
        if (s == null)
            return;
        int want = Math.min(WANT_SPEED, s.max);
        /* Settles ON the wanted gear rather than merely raising towards it.
         *
         * It used to be `cur < want`, which only ever shifts UP - correct while the target was the
         * top gear, since there was nothing above it to come down from. With the target at run,
         * that test silently does nothing for the case that matters most: a character left on
         * sprint by the player, or by a previous build, reads cur=3 against want=2, fails the
         * comparison, and sprints for the whole shift - paying for it in water trips - while this
         * method sits there believing it has set the speed. */
        if (s.cur != want)
            s.set(want);
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
