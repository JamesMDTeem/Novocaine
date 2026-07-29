package haven.automated.nbots.task;

import haven.automated.AUtils;
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
    private final double target;

    public Drink() {
        this(0.9);
    }

    public Drink(double target) {
        this.target = target;
    }

    @Override
    public Outcome run(BotCtx ctx) throws InterruptedException {
        if (ctx.stamina() >= target)
            return Outcome.ok();

        AUtils.drinkTillFull(ctx.gui, target, target);
        ctx.nav.waitUntil(() -> ctx.stamina() >= target, 60);
        if (ctx.stamina() >= target)
            return Outcome.ok();

        /* Second go, over everything the character is carrying rather than only where the client's
         * own drink looks - open inventory windows, and two equipment slots checked for a bucket.
         * A waterskin worn anywhere else is invisible to that, and invisible reads exactly like
         * absent. See Carried. */
        if (Carried.drink(ctx)) {
            ctx.nav.waitUntil(() -> ctx.stamina() >= target, 120);
            if (ctx.stamina() >= target)
                return Outcome.ok();
        }

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

        double before = ctx.stamina();
        AUtils.drinkTillFull(ctx.gui, target, target);
        ctx.nav.waitUntil(() -> ctx.stamina() >= target, 60);
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

    @Override
    public String label() {
        return "drink";
    }
}
