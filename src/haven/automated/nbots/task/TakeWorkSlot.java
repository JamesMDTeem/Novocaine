package haven.automated.nbots.task;

import haven.Gob;
import haven.automated.nbots.core.BotCtx;
import haven.automated.nbots.core.NBotConfig;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.core.Task;
import haven.automated.nbots.world.BotNav;
import haven.automated.nbots.world.Crowd;
import haven.automated.nbots.world.WorkClaims;
import haven.automated.nbots.world.WorkSlots;

import java.util.List;

/**
 * Reserves somewhere to stand while working a target, and walks there.
 *
 * The two-stage check is the whole multi-bot story in one place. Asking whether anyone is visibly
 * standing there catches every client, including ones that have never heard of this fork; asking
 * whether anyone has RESERVED it catches our own instances in the window between deciding to go
 * somewhere and arriving, which nothing observable can cover - two idle bots a hundred tiles apart
 * choosing the same rock look identical to two bots choosing different ones.
 *
 * Slots are tried nearest-first, so a bot takes the side of the object it is already coming in from
 * rather than walking around it.
 *
 * Holding a slot is stateful, which is why this is an object rather than a static helper: the
 * caller keeps the instance for as long as it means to work there, calls {@link #renew} while the
 * job runs, and {@link #release}s it in a finally. A dropped reservation expires on its own, so the
 * worst case of forgetting is a slot that stays reserved for one TTL.
 */
public class TakeWorkSlot implements Task {
    private final Gob target;

    private WorkSlots slots;
    private int slot = -1;
    private String key;
    private long lastRenew;

    public TakeWorkSlot(Gob target) {
        this.target = target;
    }

    @Override
    public Outcome run(BotCtx ctx) throws InterruptedException {
        release();
        if (target == null)
            return Outcome.failed("no target");
        WorkSlots ring = WorkSlots.around(target);
        Gob me = ctx.player();
        if (ring == null || me == null)
            return Outcome.failed("no target");

        List<Gob> others = Crowd.others(ctx.gui);
        boolean anyFree = false;

        for (int i : ring.nearestFirst(me.rc)) {
            if (!ctx.running())
                throw new InterruptedException();
            if (!ring.plausible(ctx.gui, i))
                continue;
            // Someone is already standing here. Checked before claiming so we don't reserve a spot
            // a non-participating client is visibly occupying.
            if (NBotConfig.on(NBotConfig.Key.avoidOthers)
                && Crowd.occupied(others, ring.at(i), Crowd.PERSONAL_SPACE))
                continue;
            String k = WorkClaims.slotKey(ring.gobid, i);
            if (!WorkClaims.claim(k))
                continue;

            anyFree = true;
            slots = ring;
            slot = i;
            key = k;
            lastRenew = System.currentTimeMillis();
            if (walkInto(ctx, ring, i))
                return Outcome.ok();
            release();
        }

        // Every slot taken is a temporary condition - somebody will finish and move on - while
        // every slot being unreachable is not. Distinguishing them lets the caller come back to a
        // busy tree and give up on a walled-off one.
        return anyFree ? Outcome.blocked("couldn't reach a free spot on " + ring)
                       : Outcome.blocked("every spot on " + ring + " is taken");
    }

    /**
     * Gets into the reserved slot. Falls back to a plain approach if the exact spot can't be
     * reached: standing a little off the mark and still being in range to work beats abandoning a
     * perfectly good target because one square was awkward.
     */
    private boolean walkInto(BotCtx ctx, WorkSlots ring, int i) throws InterruptedException {
        if (ctx.nav.stepTo(ring.at(i), 11 * 1.5) && Approach.inRange(ctx, target))
            return true;
        if (ctx.nav.approach(target, BotNav.REACH))
            return true;
        return Approach.inRange(ctx, target);
    }

    /** Keeps the reservation alive while a long job runs. Cheap enough to call every poll. */
    public void renew() {
        if (key == null)
            return;
        long now = System.currentTimeMillis();
        if (now - lastRenew < WorkClaims.RENEW_MS)
            return;
        lastRenew = now;
        WorkClaims.renew(key);
    }

    public void release() {
        if (key == null)
            return;
        WorkClaims.release(key);
        key = null;
        slots = null;
        slot = -1;
    }

    public boolean held() {
        return key != null;
    }

    @Override
    public String label() {
        return "take slot";
    }
}
