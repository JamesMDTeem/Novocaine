package haven.automated.lp;

import haven.Gob;
import haven.WItem;

import java.util.List;

/**
 * One candidate action that could yield a still-undiscovered LP product - either on a world gob
 * (walk to it, right-click, pick a flower-menu option) or on an inventory item (right-click it
 * where it sits).
 *
 * Deliberately holds a LIST of candidate flower options rather than one, because the option a gob
 * actually offers isn't reliably derivable from its resource name: the same standing tree offers
 * "Take bark" only while it has bark, a carcass offers "Skin" then "Butcher" as it's processed,
 * and a species new to the game may offer something not in our data at all. The executor opens
 * the real flower menu and takes the first candidate the menu actually has, so a wrong guess
 * costs a skipped task rather than a failed action.
 */
public class LpTask {
    /** Ordered cost tiers. Everything in a cheaper tier is done before anything in a dearer one. */
    public static final int TIER_INVENTORY = 0;   // no travel at all
    public static final int TIER_HARVEST   = 10;  // pick/take from a standing gob, non-destructive
    public static final int TIER_MINE      = 20;  // chip stone - slow, but leaves the gob standing
    public static final int TIER_PROCESS   = 30;  // work a felled log into boards/blocks
    public static final int TIER_FELL      = 40;  // chop a standing tree down - destructive, last resort

    public final Gob gob;              // null for inventory tasks
    public final WItem item;           // null for world tasks
    public final List<String> options; // candidate flower-menu options, best first
    public final LpAlias tool;         // tool that must be equipped first, or null
    public final int tier;
    public final String why;           // the undiscovered product this is aimed at, for logging

    private LpTask(Gob gob, WItem item, List<String> options, LpAlias tool, int tier, String why) {
        this.gob = gob;
        this.item = item;
        this.options = options;
        this.tool = tool;
        this.tier = tier;
        this.why = why;
    }

    public static LpTask onGob(Gob gob, List<String> options, LpAlias tool, int tier, String why) {
        return new LpTask(gob, null, options, tool, tier, why);
    }

    public static LpTask onItem(WItem item, List<String> options, LpAlias tool, String why) {
        return new LpTask(null, item, options, tool, TIER_INVENTORY, why);
    }

    public boolean isItem() {
        return item != null;
    }

    /** Stable identity for "this exact action on this exact target", for the exhausted-task set. */
    public String key(String option) {
        return (isItem() ? "i" + System.identityHashCode(item) : "g" + gob.id) + '|' + option;
    }

    @Override
    public String toString() {
        return (isItem() ? "item" : "gob " + LpExplorer.resname(gob)) + " -> " + options + " for " + why;
    }
}
