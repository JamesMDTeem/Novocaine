package haven.automated.nbots.task;

import haven.Coord;
import haven.Gob;
import haven.Loading;
import haven.Resource;
import haven.WItem;
import haven.automated.nbots.core.Alias;
import haven.automated.nbots.core.BotCtx;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.core.Task;
import haven.automated.nbots.world.Place;
import haven.automated.nbots.world.PlaceRoles;
import haven.automated.nbots.world.Places;

import java.util.ArrayList;
import java.util.List;

import static haven.OCache.posres;

/**
 * Takes everything matching a rule to wherever it belongs, and leaves it there.
 *
 * "Wherever it belongs" is the {@link Places} lookup rather than a destination the caller supplies:
 * a bot that produces stone should not have to know where stone goes, only that it has stone. That
 * indirection is what makes the next production bot cheap - it declares what it makes and the
 * routing is already solved.
 *
 * Falls back to dropping on the ground when no place will take the item AND the caller allowed it.
 * That sounds careless but is the right default for a cleanup crew: chipped stone is spoil, and a
 * bot that stops working because there is nowhere to file its rubbish is worse than one that leaves
 * a pile. Callers that care pass {@code dropIfNowhere = false} and get a failure instead.
 */
public class Deposit implements Task {
    /** Containers worth opening to put things into, in preference order. */
    private static final Alias STORAGE = new Alias("storage",
        "cupboard", "chest", "crate", "barrel", "coffer", "box");

    private final Alias what;
    private final boolean dropIfNowhere;

    public Deposit(Alias what) {
        this(what, false);
    }

    public Deposit(Alias what, boolean dropIfNowhere) {
        this.what = what;
        this.dropIfNowhere = dropIfNowhere;
    }

    @Override
    public Outcome run(BotCtx ctx) throws InterruptedException {
        List<WItem> carried = matching(ctx);
        if (carried.isEmpty())
            return Outcome.ok();

        String sample = name(carried.get(0));
        Place place = Places.accepting(ctx.gui, sample);
        if (place == null) {
            if (!dropIfNowhere)
                return Outcome.failed("nowhere tagged to take " + sample);
            dropAll(ctx, carried);
            return Outcome.ok();
        }

        Outcome t = new TravelTo(place).run(ctx);
        if (!t.isOk())
            return t;

        ctx.status("Storing " + sample + " at " + place.name + ".");
        // A place with a container puts things in it; a place without one is a heap, and dropping
        // on the ground inside it is what the player meant by tagging bare ground as a dump.
        List<Gob> containers = place.gobsWithin(ctx.gui, STORAGE);
        for (Gob c : containers) {
            if (!ctx.running())
                throw new InterruptedException();
            if (matching(ctx).isEmpty())
                return Outcome.ok();
            /* A reserved side of the container rather than a walk at it, for the same reason as
             * filling from a barrel: a crew all told to file the same kind of thing arrives at the
             * same cupboard within a second of each other, and whoever gets there second parks
             * against whoever got there first. Taking different sides is only possible if it is
             * agreed BEFORE the walk. Held across the transfer, since that is when standing there
             * matters. */
            TakeWorkSlot spot = new TakeWorkSlot(c);
            try {
                if (!spot.run(ctx).isOk())
                    continue;
                if (!open(ctx, c))
                    continue;
                transferInto(ctx);
                spot.renew();
                close(ctx);
            } finally {
                spot.release();
            }
        }

        List<WItem> left = matching(ctx);
        if (left.isEmpty())
            return Outcome.ok();
        if (place.hasRole(PlaceRoles.DUMP) || containers.isEmpty()) {
            dropAll(ctx, left);
            return Outcome.ok();
        }
        return Outcome.blocked("storage at " + place.name + " is full");
    }

    // ------------------------------------------------------------------ moving items

    private void transferInto(BotCtx ctx) throws InterruptedException {
        for (WItem wi : matching(ctx)) {
            if (!ctx.running())
                throw new InterruptedException();
            // The client's own "send this to the other inventory" gesture: fewer messages than
            // take-then-drop-at-a-coordinate, and it picks a free slot itself.
            wi.item.wdgmsg("transfer", Coord.z, 1);
            ctx.nav.pause(2);
        }
        ctx.nav.pause(6);
    }

    private void dropAll(BotCtx ctx, List<WItem> items) throws InterruptedException {
        Gob me = ctx.player();
        if (me == null)
            return;
        for (WItem wi : items) {
            if (!ctx.running())
                throw new InterruptedException();
            wi.item.wdgmsg("take", Coord.z);
            ctx.nav.waitUntil(() -> ctx.gui.vhand != null, 20);
            ctx.gui.map.wdgmsg("drop", Coord.z, me.rc.floor(posres), 0);
            ctx.nav.waitUntil(() -> ctx.gui.vhand == null, 20);
        }
    }

    private List<WItem> matching(BotCtx ctx) {
        List<WItem> out = new ArrayList<>();
        if (ctx.gui.maininv == null)
            return out;
        List<WItem> items;
        synchronized (ctx.gui.maininv.wmap) {
            items = new ArrayList<>(ctx.gui.maininv.wmap.values());
        }
        for (WItem wi : items) {
            String n = name(wi);
            String res = resname(wi);
            if (what.matches(n) || what.matchesPart(res))
                out.add(wi);
        }
        return out;
    }

    private static String name(WItem wi) {
        try {
            return wi.item.getname();
        } catch (Loading l) {
            return null;
        }
    }

    private static String resname(WItem wi) {
        try {
            Resource r = wi.item.getres();
            return (r == null) ? null : r.name;
        } catch (Loading l) {
            return null;
        }
    }

    // ------------------------------------------------------------------ containers

    private boolean open(BotCtx ctx, Gob c) throws InterruptedException {
        int before = ctx.gui.getAllInventories().size();
        ctx.gui.map.wdgmsg("click", Coord.z, c.rc.floor(posres), 3, 0, 0, (int) c.id,
            c.rc.floor(posres), 0, -1);
        ctx.nav.waitUntil(() -> ctx.gui.getAllInventories().size() > before, 60);
        return ctx.gui.getAllInventories().size() > before;
    }

    private void close(BotCtx ctx) throws InterruptedException {
        ctx.gui.map.wdgmsg("gk", 27);
        ctx.nav.pause(4);
    }

    @Override
    public String label() {
        return "deposit " + what;
    }
}
