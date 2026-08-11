package haven.automated.nbots.task;

import haven.Coord;
import haven.Equipory;
import haven.GItem;
import haven.GameUI;
import haven.Inventory;
import haven.Loading;
import haven.WItem;
import haven.Widget;
import haven.automated.nbots.core.BotCtx;
import haven.automated.nbots.core.NBotConfig;
import haven.automated.nbots.core.NLog;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.world.BotNav;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Swapping the tool in the character's hands for the one the current job needs.
 *
 * The cleanup bot is the reason this exists. Its jobs need three different two-handed tools -
 * an axe to chop, a pickaxe to chip stone, a shovel to clear stumps and soil - and a character can
 * hold exactly one of them. Without this the bot could only ever do whichever job matched the tool
 * the player happened to leave equipped, which is why the stock version quietly does nothing to
 * three of its five checkboxes unless you babysit it.
 *
 * Hurricane already has {@link haven.automated.EquipFromBelt}, but it is a set of hardcoded menu
 * actions ("Equip_MetalShovel"), one per specific item, that only ever look in the belt. A bot
 * needs the opposite shape: "give me any shovel, from wherever it is". So this searches the belt,
 * the pack, and any open container of the player's own, and matches on tool KIND rather than on
 * one exact resource - a wooden, tinker's or metal shovel are all a shovel as far as a stump is
 * concerned.
 *
 * The swap itself is a straight exchange: take the tool, drop it on the hand slot, and put whatever
 * was displaced into the space the tool just left. That is 1:1 and needs no free space, which is
 * the common case here since every tool involved is two-handed. Two one-handers being displaced by
 * a two-hander needs one extra free slot, and if there isn't one the swap is refused with a message
 * rather than something being dropped on the floor.
 */
public class ToolSwap {
    /** Left hand. Equipping a two-hander here fills both hands. */
    private static final int HAND = 6;
    private static final int OFFHAND = 7;

    /**
     * A tool by job, matched on resource-name fragments so every material variant counts.
     *
     * Matched against the resource name rather than the item's display name because display names
     * are localised and vary by quality prefix, while resource names don't move.
     */
    public enum Kind {
        AXE("axe", new String[] {"/axe", "/woodsmansaxe", "/b12axe", "/bronzeaxe", "/stoneaxe", "/butcherscleaver"}),
        PICK("pickaxe", new String[] {"/pickaxe", "/stonepick"}),
        SHOVEL("shovel", new String[] {"/shovel"});

        public final String label;
        private final String[] fragments;

        Kind(String label, String[] fragments) {
            this.label = label;
            this.fragments = fragments;
        }

        public boolean matches(String resname) {
            if (resname == null)
                return false;
            String n = resname.toLowerCase();
            for (String f : fragments) {
                if (n.contains(f))
                    return true;
            }
            return false;
        }
    }

    private final BotCtx ctx;
    private final GameUI gui;
    private final BotNav nav;
    private final String log;

    public ToolSwap(BotCtx ctx) {
        this.ctx = ctx;
        this.gui = ctx.gui;
        this.nav = ctx.nav;
        this.log = ctx.log;
    }

    /** True if a tool of this kind is already in hand. */
    public boolean equipped(Kind kind) {
        Equipory e = gui.getequipory();
        if (e == null)
            return false;
        for (int slot : new int[] {HAND, OFFHAND}) {
            WItem wi = e.slots[slot];
            if (wi == null)
                continue;
            try {
                if (kind.matches(wi.item.getres().name))
                    return true;
            } catch (Loading | NullPointerException ignored) {
            }
        }
        return false;
    }

    /** {@link #equip} as an outcome, for task callers. */
    public Outcome require(Kind kind) throws InterruptedException {
        if (equipped(kind))
            return Outcome.ok();
        if (!NBotConfig.on(NBotConfig.Key.autoEquipTools))
            return Outcome.failed("no " + kind.label + " in hand and auto-equip is off");
        // A missing tool is FAILED, not blocked: no amount of waiting produces a shovel, so every
        // target needing one should be dropped from the plan rather than retried in turn.
        return equip(kind) ? Outcome.ok() : Outcome.failed("no " + kind.label + " available");
    }

    /**
     * Makes sure a tool of this kind is in hand, fetching one from a pocket if need be.
     *
     * @return true if the character is now holding one.
     */
    public boolean equip(Kind kind) throws InterruptedException {
        if (equipped(kind))
            return true;
        if (!NBotConfig.on(NBotConfig.Key.autoEquipTools))
            return false;

        Equipory equipory = gui.getequipory();
        if (equipory == null)
            return false;

        Found found = find(kind);
        if (found == null) {
            NLog.log(log, "no " + kind.label + " anywhere on the character");
            return false;
        }

        // How many items the swap will displace: a two-hander occupies both slots but is one item,
        // which is why this compares identity rather than counting non-null slots.
        WItem left = equipory.slots[HAND], right = equipory.slots[OFFHAND];
        int displaced = 0;
        if (left != null)
            displaced++;
        if (right != null && (left == null || right.item != left.item))
            displaced++;

        // More than one item coming out, one item going in: somewhere has to absorb the extra.
        Coord spare = null;
        if (displaced > 1) {
            spare = found.inv.isRoom(1, 1);
            if (spare == null && gui.maininv != null && gui.maininv != found.inv)
                spare = gui.maininv.isRoom(1, 1);
            if (spare == null) {
                NLog.log(log, "no room to put down both held items while equipping a " + kind.label);
                java.awt.EventQueue.invokeLater(
                    () -> gui.error("Free one inventory slot so the bot can swap to a " + kind.label + "."));
                return false;
            }
        }
        final Inventory spareInv = (spare == null) ? null
            : (found.inv.isRoom(1, 1) != null ? found.inv : gui.maininv);

        if (!take(found.item))
            return false;
        equipory.wdgmsg("drop", HAND);
        nav.pause(3);

        // Whatever came off the hands is now on the cursor. Put it where the tool used to be, and
        // any second item in the spare slot found above.
        if (gui.vhand != null) {
            found.inv.wdgmsg("drop", found.at);
            nav.waitUntil(() -> gui.vhand == null, 20);
        }
        if (gui.vhand != null && spareInv != null) {
            spareInv.wdgmsg("drop", spare);
            nav.waitUntil(() -> gui.vhand == null, 20);
        }
        if (gui.vhand != null) {
            // Should not happen given the checks above, but leaving an item stuck on the cursor
            // blocks every subsequent pickup, so put it back on the hands and give up on the swap
            // rather than working one-handed with a phantom item attached to the mouse.
            equipory.wdgmsg("drop", HAND);
            nav.waitUntil(() -> gui.vhand == null, 20);
            NLog.log(log, "swap to " + kind.label + " left an item in hand - reverted");
            return false;
        }

        nav.waitUntil(() -> equipped(kind), 40);
        boolean ok = equipped(kind);
        NLog.log(log, (ok ? "equipped " : "failed to equip ") + kind.label);
        return ok;
    }

    private boolean take(WItem item) throws InterruptedException {
        item.item.wdgmsg("take", Coord.z);
        nav.waitUntil(() -> gui.vhand != null, 20);
        return gui.vhand != null;
    }

    // ------------------------------------------------------------------ finding a tool

    private static final class Found {
        final WItem item;
        final Inventory inv;
        final Coord at;

        Found(WItem item, Inventory inv, Coord at) {
            this.item = item;
            this.inv = inv;
            this.at = at;
        }
    }

    /**
     * The first tool of this kind anywhere the character can reach it, together with which
     * container it's in and the grid position it occupies - both needed so the displaced tool can
     * be put back in exactly the space this one vacates.
     *
     * Belt first, then the pack, then anything else open: the belt is where tools are normally
     * kept, and preferring it means the two tools keep trading the same slot instead of slowly
     * migrating through the inventory.
     */
    private Found find(Kind kind) {
        for (Inventory inv : searchOrder()) {
            for (Map.Entry<GItem, WItem> e : snapshot(inv).entrySet()) {
                try {
                    if (!kind.matches(e.getKey().getres().name))
                        continue;
                    return new Found(e.getValue(), inv, gridpos(e.getValue()));
                } catch (Loading | NullPointerException ignored) {
                }
            }
        }
        return null;
    }

    private List<Inventory> searchOrder() {
        List<Inventory> out = new ArrayList<>();
        Inventory belt = belt();
        if (belt != null)
            out.add(belt);
        if (gui.maininv != null && gui.maininv != belt)
            out.add(gui.maininv);
        for (Inventory inv : gui.getAllInventories()) {
            if (!out.contains(inv))
                out.add(inv);
        }
        return out;
    }

    private Inventory belt() {
        for (Widget w = gui.lchild; w != null; w = w.prev) {
            if (!(w instanceof GItem.ContentsWindow) || !((GItem.ContentsWindow) w).myOwnEquipory)
                continue;
            if (!((GItem.ContentsWindow) w).cap.contains("Belt"))
                continue;
            for (Widget ww : w.children()) {
                Inventory inv = Inventory.fromWidget(ww);
                if (inv != null)
                    return inv;
            }
        }
        return null;
    }

    private Map<GItem, WItem> snapshot(Inventory inv) {
        synchronized (inv.wmap) {
            return new java.util.HashMap<>(inv.wmap);
        }
    }

    /**
     * An item's position in inventory GRID coordinates.
     *
     * WItem.c is in pixels, offset by the one-pixel inventory border, and every "drop into this
     * slot" message wants grid coordinates - so this is the inverse of the mapping the inventory
     * uses to lay items out. (EquipFromBelt arrives at the same thing by scanning every grid
     * position and comparing pixel coordinates; this is the same conversion done directly.)
     */
    private Coord gridpos(WItem wi) {
        return wi.c.sub(1, 1).div(Inventory.sqsz);
    }
}
