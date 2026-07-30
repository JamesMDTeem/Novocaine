package haven.automated.nbots.core;

import haven.Coord;
import haven.Equipory;
import haven.GameUI;
import haven.GItem;
import haven.Inventory;
import haven.ItemInfo;
import haven.WItem;
import haven.automated.AUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the character is actually carrying, for the decisions that turn on it.
 *
 * There is one such decision and it was being got wrong every shift: whether a bot with no stamina
 * needs a drink or needs a trip to a water barrel. The client's own {@code GameUI.drink} answers
 * that by looking through every OPEN inventory window plus exactly two equipment slots, checked
 * only for a water bucket. A waterskin anywhere else is invisible to it, and invisible reads
 * identical to absent: the bot reported "nothing to drink", walked across the base, filled a vessel
 * that was already full, walked back, and was no better off. Then did it again.
 *
 * So the search is done here as well, over everything the client can see the character holding, and
 * the answer is used for two different things - to have another go at drinking, and, when even that
 * fails, to say that a barrel is not the answer. Those are worth separating: a bot that cannot drink
 * from a full waterskin has a problem that walking somewhere cannot fix, and the log should say so
 * rather than showing a round trip every two minutes.
 *
 * Deliberately does NOT try to open anything. A closed belt's contents do not exist client-side at
 * all - the server sends them when the window opens - so there is nothing to find and nothing this
 * could usefully say about it. {@link #describe} makes that visible instead of guessing.
 */
public class Carried {
    /**
     * Vessels worth looking inside. The same list the client's own drink uses, since it is these
     * names the game gives them; matched as substrings because the item name carries a quality and
     * sometimes a maker.
     */
    private static final String[] VESSELS =
        {"Waterskin", "Waterflask", "Kuksa", "Bucket", "Jug", "glassjug"};

    /** Polls (of 25ms) to wait for one drink to take effect. */
    private static final int SIP_TICKS = 60;
    /** Polls to wait for the drink action to appear before deciding it is not a timed one. */
    private static final int ACTION_TICKS = 8;

    private Carried() {}

    /**
     * Every vessel the character has on them, wherever the client can currently see it.
     *
     * Keyed by identity into a map only to keep the order stable and the duplicates out: the main
     * inventory is reachable both as itself and as a window, and an item found twice would be
     * drunk from twice.
     */
    public static List<WItem> vessels(GameUI gui) {
        Map<WItem, Boolean> found = new LinkedHashMap<>();
        if (gui == null)
            return new ArrayList<>();
        try {
            for (Inventory inv : gui.getAllInventories()) {
                for (WItem wi : inv.getItemsPartial(VESSELS))
                    found.put(wi, Boolean.TRUE);
            }
        } catch (RuntimeException e) {
            // A widget tree being rebuilt under us; whatever was collected is still usable.
        }
        /* Every equipment slot, not the two the client checks. A waterskin is worn as often as it
         * is carried, and the slot it hangs in is not one of those two - which is the whole reason
         * a character with water on their belt was told there was nothing to drink. */
        try {
            Equipory eq = gui.getequipory();
            if (eq != null) {
                for (WItem wi : eq.slots) {
                    if ((wi != null) && isVessel(wi))
                        found.put(wi, Boolean.TRUE);
                }
            }
        } catch (RuntimeException e) {
        }
        return new ArrayList<>(found.keySet());
    }

    /** Those of them with water in. Tea is deliberately not counted - it is not what a refill makes. */
    public static List<WItem> holdingWater(GameUI gui) {
        List<WItem> out = new ArrayList<>();
        for (WItem wi : vessels(gui)) {
            if ("Water".equals(contents(wi)))
                out.add(wi);
        }
        return out;
    }

    /**
     * Drinks from the first carried vessel that is KNOWN to have water in it.
     *
     * It briefly also drank from vessels whose contents the client could not read. The reasoning
     * was sound - {@code GItem.getcontents} swallows {@link haven.Loading} and returns null, so
     * "not sent yet" is indistinguishable from "empty", and a full waterskin can read as empty and
     * send the bot to a barrel for nothing - and the remedy was not. The vessel chosen is whichever
     * unreadable one comes first, which in practice was a FLASK, and
     * {@code clickWItemAndSelectOption(.., 0)} takes whatever that item's first menu option happens
     * to be. Drinking an unknown thing from an unknown container because we could read neither is a
     * worse failure than one wasted walk, and it is what "we randomly stopped in the middle and
     * began drinking from our flask" was.
     *
     * The race is real and is now handled where it can be handled honestly: {@code Drink} waits for
     * the contents to resolve before concluding that a vessel is empty.
     *
     * @return true if a drink was actually issued - not that it worked, which the caller decides
     *         by watching the stamina meter, the same way the client's own drink loop does.
     */
    public static boolean drink(BotCtx ctx) throws InterruptedException {
        List<WItem> wet = holdingWater(ctx.gui);
        if (wet.isEmpty())
            return false;
        WItem vessel = wet.get(0);
        // Matching GameUI.drink: the click coordinate has to be cleared or the menu opens relative
        // to wherever the mouse last was.
        ctx.gui.ui.lcc = Coord.z;
        AUtils.clickWItemAndSelectOption(ctx.gui, vessel, 0);
        /* Wait for the ACTION, not for a fixed span.
         *
         * Drinking is timed and runs a progress bar, and a fresh click on a vessel CANCELS whatever
         * action is running. A blind pause is therefore wrong in both directions at once: too short
         * and the caller's next sip aborts a mouthful halfway through, which is the stutter a
         * character shows when it is told to drink several times in a row; too long and every sip
         * of a drink to full costs a second and a half whether or not anything is still happening.
         *
         * The progress bar not appearing at all is a perfectly ordinary answer - a sip small enough
         * to be instant - so the first wait is short and its expiry means nothing. The second is
         * the real one, and it ends the moment the bar goes away. */
        ctx.nav.waitUntil(() -> ctx.gui.prog != null, ACTION_TICKS);
        ctx.nav.waitUntil(() -> ctx.gui.prog == null, SIP_TICKS);
        return true;
    }

    /**
     * What is being carried and what is in it, for a log line.
     *
     * Worth spelling out because every way this fails looks the same from outside - no vessel, a
     * vessel whose name has not resolved, a vessel whose contents the server has not sent, a vessel
     * of tea - and they want quite different things done about them.
     */
    public static String describe(GameUI gui) {
        List<WItem> all = vessels(gui);
        if (all.isEmpty())
            return "carrying no drinking vessel the client can see";
        StringBuilder sb = new StringBuilder("carrying");
        for (WItem wi : all) {
            String what = contents(wi);
            sb.append(' ').append(name(wi)).append('(')
              .append((what == null) ? "empty or unreadable" : what).append(')');
        }
        return sb.toString();
    }

    private static boolean isVessel(WItem wi) {
        String n = name(wi);
        for (String v : VESSELS) {
            if (n.contains(v))
                return true;
        }
        return false;
    }

    private static String name(WItem wi) {
        try {
            GItem it = wi.item;
            return (it == null) ? "" : it.getname();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** What is in this vessel, or null if it is empty or the client cannot tell yet. */
    private static String contents(WItem wi) {
        try {
            GItem it = wi.item;
            ItemInfo.Contents cont = (it == null) ? null : it.getcontents();
            return ((cont == null) || (cont.content == null)) ? null : cont.content.name;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
