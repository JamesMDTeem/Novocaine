package haven.automated.nbots.task;

import haven.automated.nbots.core.BotCtx;
import haven.automated.nbots.core.Carried;
import haven.automated.nbots.core.NBotConfig;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.core.Task;

/**
 * Restores stamina, going and refilling if the carried containers are empty.
 *
 * The two-step is the point. Drinking is free and instant while there is water in a waterskin, so
 * it is always tried first; only when that fails to move the meter is the trip to a water place
 * worth making. Getting this the other way round - checking whether containers look full before
 * drinking - means inspecting item contents, which is more code and less reliable than simply
 * observing that the stamina bar didn't move.
 */
public class Drink implements Task {
    /** A bound on repeated sips, so a vessel that never registers as drunk cannot spin here. */
    private static final int MAX_SIPS = 30;

    /**
     * How full to drink to. All the way.
     *
     * It was nine tenths, which is the level below which a drink is worth HAVING, and using one
     * number for both meant a bot stopped drinking at the point it would have started. Stamina is
     * what every walk spends, a mouthful is free once the vessel is in hand, and the expensive part
     * of a drink - noticing, stopping work, possibly a trip - has already been paid by the time we
     * are here. Stopping short of full just brings the next stop forward.
     */
    private static final double FULL = 1.0;

    /**
     * Above this, not being full is no reason to walk to a barrel.
     *
     * Kept apart from {@link #FULL} deliberately, because the two questions are different: how far
     * to keep drinking, and whether what we managed was worth an errand. Sharing one number in the
     * other direction is a trap - drinking to nine tenths and then testing against the same nine
     * tenths reports failure over a rounding error, and the caller reads a failed drink as a reason
     * to defer the work the drink was for.
     */
    private static final double ENOUGH = 0.9;

    private final double target;

    public Drink() {
        this(FULL);
    }

    public Drink(double target) {
        this.target = target;
    }

    @Override
    public Outcome run(BotCtx ctx) throws InterruptedException {
        if (ctx.stamina() >= target)
            return Outcome.ok();

        sip(ctx);
        if (ctx.stamina() >= enough())
            return Outcome.ok();

        /* Carrying water and still not drinking is not thirst, and a barrel cannot cure it: the
         * trip ends by filling a vessel that was already full and coming back no better off, which
         * is what a chopping shift spent its time doing. Say what is actually being carried, since
         * every way this fails looks the same from outside. */
        if (!Carried.holdingWater(ctx.gui).isEmpty())
            return Outcome.blocked("can't drink though " + Carried.describe(ctx.gui)
                + " - not walking to a barrel over it");

        if (!NBotConfig.on(NBotConfig.Key.autoRefillWater))
            return Outcome.failed("out of water (auto-refill is off)");

        ctx.log("stamina " + ctx.stamina() + " and " + Carried.describe(ctx.gui)
            + " - going for water");
        Outcome fill = new FillWater().run(ctx);
        if (!fill.isOk())
            return fill;

        /* The SAME drinking as before the trip, and that is the whole of this fix.
         *
         * This used to call the client's own drink and nothing else - the one that searches open
         * inventory windows and two equipment slots. But the character is standing here precisely
         * BECAUSE that call did nothing a minute ago: a waterskin worn anywhere else is invisible
         * to it, and invisible reads exactly like empty, which is what sent us to the barrel. So
         * the vessel was filled, the same blind call was made again, it found nothing again, and
         * the trip ended with "drinking moved nothing" over a full waterskin.
         *
         * Reaching for a weaker tool after the errand than before it is the kind of asymmetry that
         * only shows up on the path where the strong one was needed. Both go through sip(). */
        double before = ctx.stamina();
        sip(ctx);
        /* Judged on whether drinking WORKED, not on reaching the target inside the wait.
         *
         * The target is nine tenths, and a trip for water starts at whatever is left - a third, on
         * the run this was found in. A waterskin does not hold enough to close that in one go, and
         * even when it does, the meter climbs over several seconds while the wait is a second and a
         * half. So the errand succeeded, the character came back with water in it and more stamina
         * than it left with, and this reported failure - which the caller takes as a reason to
         * defer the work the water was for. "Filled, then errored" is this line.
         *
         * Not drinking AT ALL is still worth reporting, and now means what it says: the vessel was
         * filled from that source and drinking from it moved nothing, so the source is not water. */
        if (ctx.stamina() > before)
            return Outcome.ok();
        return Outcome.failed("refilled but drinking moved nothing"
            + " - is that source actually water?");
    }

    /** The level a trip to a barrel is judged against, never above what we are drinking to. */
    private double enough() {
        return Math.min(target, ENOUGH);
    }

    /**
     * Drinks until stamina stops improving or the target is reached, by ONE means.
     *
     * It used to be two: the client's own {@code drinkTillFull} first, because it loops internally,
     * and then {@link Carried#drink} because it is the only one that finds a waterskin worn in a
     * slot the client does not check. Running both is not belt and braces, it is two hands on the
     * same wheel. Drinking is a timed action with a progress bar, and issuing a fresh {@code iact}
     * on a vessel CANCELS whatever action is running and starts again - so the second mechanism
     * interrupted the first every time round, and a character that should have drunk four mouthfuls
     * in six seconds spent that time starting and abandoning them. That is the stutter.
     *
     * {@link Carried#drink} is the one kept because it is a strict superset: every inventory the
     * client can see plus every equipment slot rather than two, and a wider list of vessels. The
     * one thing lost with the client's version is that it will fall back on TEA when there is no
     * water; that is a deliberate trade, since {@link Carried} restricts itself to water on the
     * grounds that water is what a refill trip actually produces.
     *
     * ONE SIP per call, so it has to be repeated - a mouthful does not take a character from a
     * sixth of stamina to full. Bounded, and stopped the moment a sip moves nothing, which is what
     * an empty vessel or a skin full of tea looks like from here.
     *
     * @return true if stamina rose at all.
     */
    private boolean sip(BotCtx ctx) throws InterruptedException {
        double start = ctx.stamina();
        for (int i = 0; (i < MAX_SIPS) && (ctx.stamina() < target); i++) {
            double was = ctx.stamina();
            if (!Carried.drink(ctx))
                break;
            // Waits for the METER to move, not for the target, which is several sips away - the
            // difference between a wait that ends when something happened and one that always
            // costs its whole ceiling.
            ctx.nav.waitUntil(() -> ctx.stamina() > was, 40);
            if (ctx.stamina() <= was)
                break;
        }
        return ctx.stamina() > start;
    }

    @Override
    public String label() {
        return "drink";
    }
}
