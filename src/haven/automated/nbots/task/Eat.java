package haven.automated.nbots.task;

import haven.Coord;
import haven.FlowerMenu;
import haven.Gob;
import haven.Inventory;
import haven.ItemInfo;
import haven.Loading;
import haven.WItem;
import haven.Widget;
import haven.automated.nbots.core.Alias;
import haven.automated.nbots.core.BotCtx;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.core.Task;
import haven.automated.nbots.core.Widgets;
import haven.automated.nbots.world.Place;
import haven.automated.nbots.world.PlaceRoles;
import haven.automated.nbots.world.Places;
import haven.resutil.FoodInfo;

import java.util.ArrayList;
import java.util.List;

import static haven.OCache.posres;

/**
 * Restores energy by eating.
 *
 * Order of preference, and the reasoning:
 *
 * 1. Anything already carried that is food. Free - no walk, no container to open - so it is tried
 *    first even though it is the least controlled source.
 * 2. A container inside a place tagged {@link PlaceRoles#FOOD}. This is how you decide what counts
 *    as bot fodder: by what you put in that cupboard, not by a rule the bot has to infer. Whatever
 *    is in there is fair game.
 *
 * The LP bot deliberately refuses to auto-eat and stops instead, on the grounds that eating the
 * wrong thing wastes FEP. That is the right call for a character you care about the food meter of;
 * for a worker running unattended for hours it just means the run ends early, so these bots eat.
 *
 * Eating is gated on {@link haven.automated.nbots.core.NBotConfig.Key#autoEat} so the old behaviour
 * is one toggle away.
 */
public class Eat implements Task {
    /** Containers worth opening in a food place. Not exhaustive - it is a preference order. */
    private static final Alias FOOD_CONTAINERS = new Alias("food containers",
        "cupboard", "chest", "crate", "barrel", "table", "basket");

    /** Stop once energy is back above this. Eating past it wastes food on a worker. */
    private static final double TARGET_ENERGY = 0.85;
    /** How many mouthfuls before giving up - food that restores nothing shouldn't loop. */
    private static final int MAX_BITES = 20;

    @Override
    public Outcome run(BotCtx ctx) throws InterruptedException {
        if (ctx.energy() >= TARGET_ENERGY)
            return Outcome.ok();

        ctx.status("Eating.");
        if (eatFromPack(ctx) && ctx.energy() >= TARGET_ENERGY)
            return Outcome.ok();

        /* Whatever is already open, before considering a walk. Most often that is a table the
         * player has right-clicked - which is exactly where a meal is supposed to happen, and
         * carries the table's own FEP bonus - but any open container will do.
         *
         * This used to be reachable only AFTER travelling to a food place and opening a container
         * from the inside, so a bot standing at a laid table with the window open in front of it
         * would walk across the map to a cupboard, or fail outright when no food place was
         * defined. The scan is free; the window is already there. */
        if (takeFoodFromOpenContainers(ctx)) {
            eatFromPack(ctx);
            if (ctx.energy() >= TARGET_ENERGY)
                return Outcome.ok();
        }

        Place place = Places.nearest(ctx.gui, PlaceRoles.FOOD);
        if (place == null)
            return Outcome.failed("nothing edible carried and no place tagged for food");

        Outcome t = new TravelTo(place).run(ctx);
        if (!t.isOk())
            return t;

        // Take food out of whatever is here into the pack, then eat it the same way as above -
        // rather than eating out of the container directly, which needs the item to stay put while
        // a menu opens over it and is fiddly to get right across every container type.
        if (!stockUpFrom(ctx, place))
            return Outcome.failed("found nothing to eat in " + place.name);
        eatFromPack(ctx);

        return ctx.energy() >= TARGET_ENERGY ? Outcome.ok()
            : Outcome.blocked("ate what there was and energy is still low");
    }

    // ------------------------------------------------------------------ eating

    /** @return true if at least one bite was taken. */
    private boolean eatFromPack(BotCtx ctx) throws InterruptedException {
        boolean ate = false;
        for (int bite = 0; bite < MAX_BITES && ctx.running(); bite++) {
            if (ctx.energy() >= TARGET_ENERGY)
                break;
            WItem food = firstFood(ctx);
            if (food == null)
                break;
            double before = ctx.energy();
            if (!eatOne(ctx, food))
                break;
            ate = true;
            // If a whole item went down and energy didn't move, this is not food that helps -
            // stop rather than working through the entire pack one useless item at a time.
            if (ctx.energy() <= before + 0.001 && firstFood(ctx) != null)
                break;
        }
        return ate;
    }

    private boolean eatOne(BotCtx ctx, WItem food) throws InterruptedException {
        haven.automated.eat.EatObserver.onIact(food.item);
        food.item.wdgmsg("iact", food.c, 0);
        FlowerMenu fm = awaitMenu(ctx);
        if (fm == null)
            return false;
        for (FlowerMenu.Petal petal : fm.opts) {
            if ("Eat".equals(petal.name)) {
                fm.wdgmsg("cl", petal.num, 0);
                ctx.nav.waitUntil(() -> liveMenu(ctx) == null, 50);
                ctx.nav.waitUntil(() -> ctx.poseContains("idle"), 100);
                return true;
            }
        }
        fm.wdgmsg("cl", -1);
        ctx.nav.waitUntil(() -> liveMenu(ctx) == null, 50);
        return false;
    }

    /** The first carried item with food value. */
    private WItem firstFood(BotCtx ctx) {
        if (ctx.gui.maininv == null)
            return null;
        for (WItem wi : snapshot(ctx.gui.maininv)) {
            if (isFood(wi))
                return wi;
        }
        return null;
    }

    private static boolean isFood(WItem wi) {
        try {
            List<ItemInfo> infos = wi.item.info();
            if (infos == null)
                return false;
            for (ItemInfo ii : infos) {
                if (ii instanceof FoodInfo)
                    return true;
            }
        } catch (Loading l) {
            // Info not ready this pass - treat as not-food; it'll be reconsidered next time.
        }
        return false;
    }

    // ------------------------------------------------------------------ restocking

    /**
     * Opens containers in the food place and moves anything edible into the pack.
     *
     * @return true if anything was taken.
     */
    private boolean stockUpFrom(BotCtx ctx, Place place) throws InterruptedException {
        List<Gob> containers = place.gobsWithin(ctx.gui, FOOD_CONTAINERS);
        for (Gob c : containers) {
            if (!ctx.running())
                throw new InterruptedException();
            // A reserved side, as with any other container a crew converges on - see Deposit. A
            // meal break is exactly when several bots arrive at one cupboard together.
            TakeWorkSlot spot = new TakeWorkSlot(c);
            try {
                if (!spot.run(ctx).isOk())
                    continue;
                if (!open(ctx, c))
                    continue;
                boolean took = takeFoodFromOpenContainers(ctx);
                close(ctx);
                if (took)
                    return true;
            } finally {
                spot.release();
            }
        }
        return false;
    }

    private boolean open(BotCtx ctx, Gob c) throws InterruptedException {
        int before = ctx.gui.getAllInventories().size();
        ctx.gui.map.wdgmsg("click", Coord.z, c.rc.floor(posres), 3, 0, 0, (int) c.id,
            c.rc.floor(posres), 0, -1);
        ctx.nav.waitUntil(() -> ctx.gui.getAllInventories().size() > before, 60);
        return ctx.gui.getAllInventories().size() > before;
    }

    private void close(BotCtx ctx) throws InterruptedException {
        // Closing by walking away is unreliable; the escape key is what the client itself binds.
        ctx.gui.map.wdgmsg("gk", 27);
        ctx.nav.pause(4);
    }

    private boolean takeFoodFromOpenContainers(BotCtx ctx) throws InterruptedException {
        boolean took = false;
        for (Inventory inv : ctx.gui.getAllInventories()) {
            if (inv == ctx.gui.maininv)
                continue;
            for (WItem wi : snapshot(inv)) {
                if (ctx.freeSpace() <= 1)
                    return took;
                if (!isFood(wi))
                    continue;
                // Shift-click is the client's own "move this to the other inventory" gesture, which
                // is both fewer messages and less to get wrong than take-then-drop-at-a-coordinate.
                wi.item.wdgmsg("transfer", Coord.z, 1);
                ctx.nav.pause(2);
                took = true;
            }
        }
        return took;
    }

    private static List<WItem> snapshot(Inventory inv) {
        synchronized (inv.wmap) {
            return new ArrayList<>(inv.wmap.values());
        }
    }

    // ------------------------------------------------------------------ menu plumbing

    private static FlowerMenu awaitMenu(BotCtx ctx) throws InterruptedException {
        return Widgets.awaitFlowerMenu(ctx.gui.ui.root, ctx::running);
    }

    private static FlowerMenu liveMenu(BotCtx ctx) {
        return Widgets.find(ctx.gui.ui.root, FlowerMenu.class);
    }

    @Override
    public String label() {
        return "eat";
    }
}
