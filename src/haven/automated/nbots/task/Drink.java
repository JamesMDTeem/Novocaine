package haven.automated.nbots.task;

import haven.automated.AUtils;
import haven.automated.nbots.core.BotCtx;
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

        if (!NBotConfig.on(NBotConfig.Key.autoRefillWater))
            return Outcome.failed("out of water (auto-refill is off)");

        ctx.log("stamina " + ctx.stamina() + " and nothing to drink - going for water");
        Outcome fill = new FillWater().run(ctx);
        if (!fill.isOk())
            return fill;

        AUtils.drinkTillFull(ctx.gui, target, target);
        ctx.nav.waitUntil(() -> ctx.stamina() >= target, 60);
        return ctx.stamina() >= target ? Outcome.ok()
            : Outcome.failed("refilled but still couldn't drink - is that source actually water?");
    }

    @Override
    public String label() {
        return "drink";
    }
}
