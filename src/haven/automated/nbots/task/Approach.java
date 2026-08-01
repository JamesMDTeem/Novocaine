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
        // Log the distance check before committing to the approach, so the gap between the
        // target's actual distance and WORK_RANGE is visible in the log when a bot calls a
        // target reachable and then fails to reach it.
        Gob me = ctx.player();
        Gob now = (target == null) ? null : ctx.gob(target.id);
        double workDist = (me == null || now == null) ? -1 : me.rc.dist(now.rc);
        ctx.log("approach: " + ((workDist < 0) ? "no player/target" :
            String.format("%.0fu to #%d, WORK_RANGE=%.0fu", workDist, target.id, WORK_RANGE)));
        if (inRange(ctx, target))
            return Outcome.ok();
        if (ctx.nav.approach(target, reach))
            return Outcome.ok();
        /* Stopping short of `reach` is not the same as being unable to work.
         *
         * The default reach is twenty-two units and {@link #WORK_RANGE} is forty-four, so there is
         * a two-tile band where the approach has failed by its own measure and a right-click would
         * land perfectly well - and nothing was looking at it. What that costs is a tree given up
         * on because the last two tiles were awkward: fallen logs lying across the tiles on the
         * near side stop the local pathfinder getting to the trunk's hitbox from here, it reports
         * failure at thirty units, and the target is retired. Standing thirty units away is a
         * perfectly good place to chop from.
         *
         * Only where the caller asked to get CLOSER than working range, which is the case this is
         * about. A caller that deliberately wanted more room than a right-click needs must not be
         * handed working range as a consolation. */
        Gob after = ctx.player();
        Gob live = ctx.gob(target.id);
        double afterDist = (after == null || live == null) ? -1 : after.rc.dist(live.rc);
        ctx.log("approach: nav approach fell short, reach=" + (int) (reach / 11) + "t,"
            + " WORK_RANGE=" + (int) (WORK_RANGE / 11) + "t,"
            + " now " + (int) (afterDist / 11) + "t from #" + target.id);
        if ((reach < WORK_RANGE) && inRange(ctx, target))
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
