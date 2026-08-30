package haven.automated.nbots.core;

import haven.Coord;
import haven.Equipory;
import haven.GameUI;
import haven.GItem;
import haven.Gob;
import haven.Inventory;
import haven.Resource;
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
     * Vessels worth looking inside, by the name the game puts on the item.
     *
     * Matched as substrings, because the item name carries a quality and sometimes a maker -
     * which is also why "Kuksa" is enough for a Birchbark Kuksa and "Jug" for a Glass Jug.
     *
     * It was the client's own list, and the client's own list has no cups in it. A character
     * whose only vessel was a Wooden Cup therefore reported "carrying no drinking vessel the
     * client can see", walked to a barrel, filled the cup - the refill scan goes by RESOURCE name
     * and has known about cups all along - came back, still could not see it, and went again.
     * That loop is the whole bug: not a failure to fetch water, a failure to notice the thing
     * the water went into.
     *
     * The cups are spelled out in full rather than matched on "Cup", which is inside Cupboard -
     * furniture a bot may well be carrying, and a vessel that holds no water but reads as one is
     * how {@link #describe} starts lying about what is on the character.
     */
    private static final String[] VESSELS =
        {"Waterskin", "Waterflask", "Kuksa", "Bucket", "Jug", "Wooden Cup", "Leaf Cup"};

    /**
     * The same vessels by RESOURCE name, which is what is actually matched on now.
     *
     * The names above need the server's tooltip message to have arrived; a resource does not, and
     * that difference is the whole of "carrying no drinking vessel the client can see" appearing
     * in a log next to a waterskin with two thirds of its water still in it. {@code GItem.getname}
     * answers a sentinel string until that message lands, the sentinel matches nothing here, no
     * vessel reads as no water, and the bot walks to a barrel it did not need.
     *
     * The window is short and a DIGGING bot hits it constantly: every shovelful puts a fresh item
     * in the inventory, and with a good metal shovel the client is describing new items more or
     * less the whole time. That is why this shows up as a digging problem when it is not one - it
     * is an item-info problem that fast digging makes continuous.
     *
     * Matched as substrings so "/waterskin" covers both "gfx/invobjs/waterskin" and the small
     * variant, and "/kuksa" covers "kuksa-full" - vessels change resource when they stop being
     * empty, so a list of exact names would see each one only half the time. Buckets are the one
     * that has to be spelled out: "bucket-water" is a drinking vessel and a bucket of clay is not.
     */
    private static final String[] VESSEL_RES =
        {"/waterskin", "/waterflask", "/kuksa", "/bucket-water", "/glassjug", "/woodencup",
         "/leafcup"};

    /**
     * "The client has not been told yet", which is emphatically NOT "empty".
     *
     * The same distinction {@code RefillWaterContainers} draws, for the same reason: those two
     * were one value, empty means "needs fetching", and so a vessel nobody had described to us yet
     * was a reason to walk across the base.
     */
    private static final ItemInfo.Contents.Content UNKNOWN =
        new ItemInfo.Contents.Content(null, null, -1);

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
                /* Every item, tested one at a time, rather than Inventory.getItemsPartial.
                 *
                 * That helper reads a name per item with no guard of its own, so a single item the
                 * client could not answer for threw straight out of the loop and past this catch -
                 * losing not just that item but every inventory that had not been looked at yet.
                 * One unreadable clod of soil in the main inventory therefore hid a waterskin on
                 * the belt, which is the flavour of "no drinking vessel the client can see" that
                 * appears and disappears between two log lines seconds apart. */
                for (WItem wi : inv.getAllItems()) {
                    try {
                        if (isVessel(wi))
                            found.put(wi, Boolean.TRUE);
                    } catch (RuntimeException e) {
                        // One item we cannot read is no reason to stop reading the rest.
                    }
                }
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
     * The largest amount each kind of vessel has been SEEN to hold, which is its capacity.
     *
     * Learned rather than tabulated. Writing "a waterskin holds 2" here would be folklore - nothing
     * in this client states it, the number is the server's, and it goes stale the first time it is
     * rebalanced. A vessel that has been full once has told us its capacity exactly, and one that
     * never has cannot be judged as a fraction at all, which {@link #waterFraction} says by
     * answering -1 rather than by guessing.
     *
     * Keyed on the vessel's own name. Concurrent because bots read this from their own threads.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Float> CAPACITY =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * How full the carried water vessels are, as a fraction of what they can hold, or -1 if that
     * cannot be worked out yet.
     *
     * -1 is "no idea", NOT "empty", and the difference matters to every caller: a bot that treats
     * an unreadable vessel as empty is the bot that walks to a barrel with a full waterskin, which
     * this class exists to stop. The same reasoning as {@code Router.walkingDistance} returning -1.
     *
     * Totalled across vessels rather than reported per vessel, because the question a caller has is
     * "can I keep drinking", and two half-full skins answer it as well as one full one.
     */
    public static double waterFraction(GameUI gui) {
        float held = 0, cap = 0;
        for (WItem wi : vessels(gui)) {
            ItemInfo.Contents.Content c = content(wi);
            if ((c == null) || (c == UNKNOWN))
                continue;
            String n = name(wi);
            if (n.isEmpty())
                continue;
            // Every vessel teaches us its capacity, whatever is in it - including tea, since the
            // container is the same size either way.
            Float seen = CAPACITY.get(n);
            if ((seen == null) || (c.count > seen))
                CAPACITY.put(n, c.count);
            Float known = CAPACITY.get(n);
            if ((known == null) || (known <= 0))
                continue;
            cap += known;
            if ("Water".equals(c.name))
                held += c.count;
        }
        return (cap <= 0) ? -1 : (held / cap);
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
        return drink(ctx.gui, ctx.nav::waitUntil);
    }

    /**
     * How the caller waits. Bots have abort-aware waits of their own and must keep using them, so
     * the wait is passed in rather than chosen here.
     */
    public interface Waiter {
        void until(haven.automated.nbots.world.BotNav.Cond cond, int ticks) throws InterruptedException;
    }

    /**
     * The same drink for a caller that has a {@link GameUI} and no bot context.
     *
     * Exists for the LP assistant, which is not an nbot and was reaching for
     * {@code AUtils.drinkTillFull} - the client's own drink, which scans inventories for a flask but
     * checks only equipment slots 6 and 7 and only for a {@code bucket-water}. A Waterflask WORN
     * anywhere is therefore invisible to it, it returns false, and the LP bot read that as "no
     * water" and ended the whole run while carrying a full flask. This one goes through
     * {@link #vessels}, which reads every equipment slot.
     *
     * Polls on its own thread and honours interruption, which is how the LP bot is stopped.
     */
    public static boolean drink(GameUI gui) throws InterruptedException {
        return drink(gui, (cond, ticks) -> {
            for (int i = 0; i < ticks; i++) {
                if (Thread.interrupted())
                    throw new InterruptedException();
                try {
                    if (cond.check())
                        return;
                } catch (haven.Loading l) {
                    // Still arriving; keep waiting.
                }
                Thread.sleep(POLL_MS);
            }
        });
    }

    /** Poll interval, matching the bots' own. */
    private static final int POLL_MS = 25;

    private static boolean drink(GameUI gui, Waiter wait) throws InterruptedException {
        List<WItem> wet = holdingWater(gui);
        if (wet.isEmpty())
            return false;
        WItem vessel = wet.get(0);
        // Matching GameUI.drink: the click coordinate has to be cleared or the menu opens relative
        // to wherever the mouse last was.
        gui.ui.lcc = Coord.z;
        AUtils.clickWItemAndSelectOption(gui, vessel, 0);
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
        /* Waited on the DRINKING POSE, not on the bare progress bar.
         *
         * {@code gui.prog} is whatever timed action happens to be running, and a bot that stops to
         * drink is by definition a bot that was in the middle of something else - digging, most of
         * all. With a good metal shovel the dig bar is up more or less continuously, so "wait for
         * a progress bar to appear" was already satisfied before the drink had begun, and "wait
         * for it to go away" ended on a shovelful finishing rather than on a mouthful. Both waits
         * returned early, the caller measured the stamina meter before the drink had moved it,
         * concluded the vessel was doing nothing, and stopped after one sip. That is a drink that
         * leaves the character part full and the waterskin nearly full, which is what gets seen as
         * "it isn't filling up after drinking".
         *
         * The pose is unambiguous - "drinkan" is the character drinking and nothing else - and it
         * cannot be satisfied by an unrelated action the way a shared progress bar can. The bar is
         * kept in the second wait only as a second way of seeing quiet. The first wait expiring
         * still means nothing: a sip small enough to be instant never poses at all. */
        wait.until(() -> drinking(gui), ACTION_TICKS);
        wait.until(() -> !drinking(gui) && (gui.prog == null), SIP_TICKS);
        return true;
    }

    /** True while the character is in the drinking animation, and false for every other action. */
    private static boolean drinking(GameUI gui) {
        try {
            Gob me = gui.map.player();
            if (me == null)
                return false;
            for (String pose : me.getPoses()) {
                if ((pose != null) && pose.contains("drinkan"))
                    return true;
            }
            return false;
        } catch (RuntimeException e) {
            // Includes Loading. Not knowing is not the same as drinking.
            return false;
        }
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
            ItemInfo.Contents.Content c = content(wi);
            // "Empty" and "not described to us yet" used to print as one string, and they call for
            // opposite things: the first is a reason to go and fill up, the second a reason to look
            // again in a moment. A log that cannot tell them apart cannot show which happened.
            String what = (c == UNKNOWN) ? "contents not sent yet"
                : (c == null) ? "empty" : c.name;
            String n = name(wi);
            if (n.isEmpty())
                n = basename(wi);
            sb.append(' ').append(n).append('(').append(what).append(')');
        }
        return sb.toString();
    }

    /**
     * Resource first, name second.
     *
     * The name list is kept rather than replaced because the two answer slightly different
     * questions - a resource fragment is exact, and a name catches anything the fragment list has
     * not been told about - and because a vessel identified either way is a vessel. What matters
     * is that the resource answer is available in the window where the name one is not.
     */
    private static boolean isVessel(WItem wi) {
        String res = resName(wi);
        if (!res.isEmpty()) {
            for (String v : VESSEL_RES) {
                if (res.contains(v))
                    return true;
            }
        }
        String n = name(wi);
        for (String v : VESSELS) {
            if (n.contains(v))
                return true;
        }
        return false;
    }

    /** The item's resource path, or "" if it has not loaded. Available before the tooltip is. */
    private static String resName(WItem wi) {
        try {
            GItem it = wi.item;
            Resource res = (it == null) ? null : it.getres();
            return (res == null) ? "" : res.name;
        } catch (RuntimeException e) {
            // Includes Loading, which for a resource is genuinely transient.
            return "";
        }
    }

    /** The last element of the resource path, to name an item whose tooltip has not arrived. */
    private static String basename(WItem wi) {
        String res = resName(wi);
        if (res.isEmpty())
            return "unnamed item";
        return res.substring(res.lastIndexOf('/') + 1);
    }

    /**
     * The item's name, or "" when the client has not been told it yet.
     *
     * {@code GItem.getname} answers with a sentinel STRING rather than throwing when it cannot
     * read the item - "it's null" before the tooltip message has arrived, "exception" when
     * building the info threw. Both look like a real name to anything that just does
     * {@code contains}, both were being matched against the vessel list as if they were one, and
     * both mean the same thing here: we do not know what this is yet. Reporting that as "" is what
     * lets the rest of this class tell not-known from not-there.
     */
    private static String name(WItem wi) {
        try {
            GItem it = wi.item;
            return (it == null) ? "" : readableName(it);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String readableName(GItem it) {
        String n;
        try {
            n = it.getname();
        } catch (RuntimeException e) {
            return "";
        }
        if ((n == null) || n.isEmpty() || n.equals("it's null") || n.equals("exception"))
            return "";
        return n;
    }

    /** What is in this vessel, or null if it is empty or the client cannot tell yet. */
    private static String contents(WItem wi) {
        ItemInfo.Contents.Content c = content(wi);
        return (c == null) ? null : c.name;
    }

    /**
     * The same reading with the AMOUNT kept, for callers that need how much rather than what.
     *
     * Null on the same terms {@link #contents} returns null on, and for the same reason: the server
     * not having sent the item's info yet is indistinguishable from an empty vessel, so neither may
     * be reported as a fact. Note {@code Contents.content} is never itself null - it is
     * {@code Content.EMPTY}, whose name is - so the name is what has to be tested.
     */
    private static ItemInfo.Contents.Content content(WItem wi) {
        try {
            GItem it = wi.item;
            if (it == null)
                return null;
            ItemInfo.Contents cont = it.getcontents();
            if ((cont != null) && (cont.content != null) && (cont.content.name != null))
                return cont.content;
            /* No contents found, which is "empty" only if the item's info is actually HERE - and
             * the way to tell is the NAME, because a name and a contents line arrive in the same
             * tooltip message. Until it lands, {@code info()} builds happily from an empty raw and
             * caches the result, so a full waterskin reads as nameless and contentless rather than
             * throwing anything for us to notice. Answering "empty" to that is precisely what sent
             * a bot to a barrel carrying 2.3 litres of water. */
            return readableName(it).isEmpty() ? UNKNOWN : null;
        } catch (RuntimeException e) {
            return UNKNOWN;
        }
    }
}
