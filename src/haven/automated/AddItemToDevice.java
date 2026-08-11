package haven.automated;

import haven.Coord;
import haven.GItem;
import haven.GameUI;
import haven.Gob;
import haven.Resource;
import haven.WItem;

import java.util.function.Predicate;

import static haven.OCache.posres;

/**
 * The one-shot "take the nearest matching item to the hand and feed it to the nearest matching
 * building" program, parameterised by what to find and what to click.
 *
 * AddBranchesToFurnace, AddCoalToSmelter and AddWoodBlocksToSmokeShed were three 97-line copies of
 * this body with the gob name, the item name and the error strings substituted. Each is now a thin
 * wrapper that supplies those as arguments. MenuGrid keeps constructing the same classes, so
 * nothing upstream changes.
 */
public class AddItemToDevice implements Runnable {
    private final GameUI gui;
    private final int count;
    private final Predicate<Resource> gobMatches;
    private final String noGobError;
    private final Predicate<String> itemMatches;
    private final String noItemError;
    private final String notEnoughError;
    private static final int TIMEOUT = 2000;
    private static final int HAND_DELAY = 8;

    public AddItemToDevice(GameUI gui, int count,
                           Predicate<Resource> gobMatches, String noGobError,
                           Predicate<String> itemMatches, String noItemError,
                           String notEnoughError) {
        this.gui = gui;
        this.count = count;
        this.gobMatches = gobMatches;
        this.noGobError = noGobError;
        this.itemMatches = itemMatches;
        this.noItemError = noItemError;
        this.notEnoughError = notEnoughError;
    }

    @Override
    public void run() {
        Gob device = BotLoop.nearestGob(gui, gobMatches);
        if (device == null) {
            gui.error(noGobError);
            return;
        }

        WItem itemw = null;
        for (WItem item : gui.getAllItemsFromAllInventoriesAndStacks()) {
            if (itemMatches.test(item.item.getname()))
                itemw = item;
        }
        if (itemw == null) {
            gui.error(noItemError);
            return;
        }
        GItem item = itemw.item;

        item.wdgmsg("take", new Coord(item.sz.x / 2, item.sz.y / 2));
        int timeout = 0;
        while (gui.hand.isEmpty() || gui.vhand == null) {
            timeout += HAND_DELAY;
            if (timeout >= TIMEOUT) {
                gui.error(noItemError);
                return;
            }
            try {
                Thread.sleep(HAND_DELAY);
            } catch (InterruptedException e) {
                return;
            }
        }
        item = gui.vhand.item;

        for (int left = count; left > 0; left--) {
            gui.map.wdgmsg("itemact", Coord.z, device.rc.floor(posres), left == 1 ? 0 : 1, 0, (int) device.id, device.rc.floor(posres), 0, -1);
            timeout = 0;
            while (true) {
                WItem newitem = gui.vhand;
                if (newitem != null && newitem.item != item) {
                    item = newitem.item;
                    break;
                } else if (newitem == null && left == 1) {
                    return;
                }

                timeout += HAND_DELAY;
                if (timeout >= TIMEOUT) {
                    gui.error(notEnoughError + " Need to add " + (left - 1) + " more.");
                    return;
                }
                try {
                    Thread.sleep(HAND_DELAY);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }
}
