package haven.automated.nbots.task;

import haven.Gob;
import haven.automated.nbots.core.BotCtx;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.core.Task;
import haven.automated.nbots.world.BotNav;

/**
 * Walks up to a gob, routing around wildlife and other characters.
 *
 * A thin task wrapper over {@link BotNav#approach}, and the reason it is worth being a task at all
 * is the outcome: the nav layer reports "couldn't get there" as a boolean plus a
 * {@code hazardBlocked} flag, and turning that pair into blocked-versus-failed here means every
 * caller gets the defer/retire decision right without having to remember the flag exists.
 */
public class Approach implements Task {
    /** Close enough that a right-click on the target will land. */
    public static final double WORK_RANGE = 11 * 4.0;

    private final Gob target;
    private final double reach;

    public Approach(Gob target) {
        this(target, BotNav.REACH);
    }

    public Approach(Gob target, double reach) {
        this.target = target;
        this.reach = reach;
    }

    @Override
    public Outcome run(BotCtx ctx) throws InterruptedException {
        if (target == null)
            return Outcome.failed("no target");
        if (inRange(ctx, target))
            return Outcome.ok();
        if (ctx.nav.approach(target, reach))
            return Outcome.ok();
        // A beast in the way is temporary; anything else means this target can't be walked to and
        // trying again would spend another walk finding that out.
        return ctx.nav.hazardBlocked
            ? Outcome.blocked("wildlife between us and the target")
            : Outcome.failed("couldn't reach the target");
    }

    /** Close enough to act on a target, re-read from the live gob in case it moved. */
    public static boolean inRange(BotCtx ctx, Gob target) {
        Gob me = ctx.player();
        Gob now = (target == null) ? null : ctx.gob(target.id);
        return me != null && now != null && me.rc.dist(now.rc) <= WORK_RANGE;
    }

    @Override
    public String label() {
        return "approach";
    }
}
