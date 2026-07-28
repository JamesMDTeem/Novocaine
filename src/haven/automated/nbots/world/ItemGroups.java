package haven.automated.nbots.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Named bundles of item names, so a storage rule can be written as "all prepared hides" rather than
 * as eleven comma-separated words.
 *
 * The problem this solves is one of arithmetic. A place that takes hides needs every hide named -
 * and there are a dozen kinds, each with a leather and a scraped form - so the rule is either
 * enormous or wrong in a way that only shows up when the one hide nobody typed turns up in the
 * pack. Groups let the common bundles be got right once.
 *
 * DELIBERATELY EXPANDED, NOT REFERENCED. Picking a group writes its members into the place's rule
 * as plain text, and the place stores that text. It would be tidier to store "@hides" and resolve
 * it at match time, and it would be worse: the player could not then see, edit or trim what the
 * rule actually covers, and a group edited in a later version would silently change what every
 * existing place accepts. What you picked is what you get, and it stays yours.
 *
 * The entries are matched as FRAGMENTS (see {@link haven.automated.nbots.core.Alias#matchesPart}),
 * which is what lets one word cover a family - "hide" catches every hide there will ever be, and
 * the longer lists here exist for the cases where no single fragment does.
 *
 * WHICH ALSO MEANS A FRAGMENT CAN CATCH THINGS NOBODY MEANT, and several here did. Checked against
 * the item names the LP assistant already carries ({@code LpSpec}, about a thousand of them), "Bar"
 * matched Barley Flour, "Ore" matched Block of Sycamore and Morels, and "Horn" matched Hornbeam
 * boards and Hornblende - so a storage place told to take metal would have taken the flour, and one
 * told to take bones would have taken the timber. The entries below are written to what the game
 * actually calls things: "Bar of", " Ore" with the space, and the horns spelled out.
 *
 * Grouped into categories, in the manner of nurgling2's specialisation list, because fourteen
 * unsorted entries in a dropdown is already a list you read rather than scan, and the answer to
 * "which one do I want" is nearly always known by category first.
 */
public class ItemGroups {
    /** Group name -> the item-name fragments it stands for, in the order they are written out. */
    public static final Map<String, List<String>> GROUPS = new LinkedHashMap<>();
    /** The category each group is filed under, for display. */
    private static final Map<String, String> CATEGORY = new LinkedHashMap<>();
    /** The separator between a category and a group name in the picker. */
    private static final String SEP = " - ";

    private static void group(String category, String name, String... items) {
        GROUPS.put(name, Arrays.asList(items));
        CATEGORY.put(name, category);
    }

    static {
        group("Animals", "Raw hides", "Hide", "Fur", "Pelt");
        group("Animals", "Prepared hides", "Leather", "Scraped Hide", "Cured Hide", "Tanned Hide");
        // Spelled out rather than "Horn": that fragment matches Hornbeam and Blackthorn timber,
        // Hornblende and Horn Silver, none of which belong in a bone store.
        group("Animals", "Bones and antler", "Bone", "Antler", "Tusk",
            "Billygoat Horn", "Wildgoat Horn", "Ramshorn");
        group("Animals", "Raw meat", "Meat", "Fillet", "Sausage Meat", "Offal");

        // "Block"/"Log" alone catch Beaver Block and Eclogite; the "of" is what makes them timber.
        group("Forestry", "Timber", "Log", "Block of", "Board of");
        group("Forestry", "Firewood and boughs", "Branch", "Bough", "Coal");
        group("Forestry", "Bark", "Bark", "Cordage");

        // A leading space on " Ore" - without it the fragment is inside Sycamore, Morels,
        // Boreworm, Shoreline and Apple Core.
        group("Mining", "Stone and ore", "Stone", " Ore", "Cinnabar", "Nugget");
        // "Bar of" rather than "Bar", which is inside Barley and Birchbark.
        group("Mining", "Bars and metal", "Bar of", "Ingot", "Wrought Iron", "Steel");
        group("Mining", "Clay and bricks", "Clay", "Brick");

        group("Textiles", "Fibre and yarn", "Fibre", "Yarn", "Wool", "Hemp", "Flax");
        group("Textiles", "Cloth", "Cloth", "Linen", "Silk");

        group("Farming", "Seeds", "Seed", "Barley", "Wheat", "Flax", "Millet", "Carrot", "Turnip");
        group("Farming", "Flour and grain", "Flour", "Grain");

        group("Food", "Cooked food", "Roast", "Stew", "Soup", "Pie", "Bread", "Porridge");
        group("Food", "Water containers", "Waterskin", "Bottle", "Bucket", "Flask", "Kuksa");

        group("Kit", "Tools", "Axe", "Pickaxe", "Shovel", "Hammer", "Saw", "Knife", "Sickle",
            "Scythe");
        group("Kit", "Beeswax and candles", "Wax", "Candle", "Honey");
    }

    private ItemGroups() {}

    /**
     * The picker's entries, as "Category - Group".
     *
     * One flat list of labelled entries rather than a nested menu: the dropdown this feeds has no
     * notion of a heading, and a prefix sorts the related entries next to each other for free -
     * which is the whole of what the categories were wanted for.
     */
    public static List<String> names() {
        List<String> out = new ArrayList<>();
        for (String name : GROUPS.keySet())
            out.add(CATEGORY.get(name) + SEP + name);
        return out;
    }

    /** The group behind a picker label, tolerating a bare group name. */
    private static String plain(String label) {
        if (label == null)
            return null;
        int at = label.indexOf(SEP);
        return (at < 0) ? label : label.substring(at + SEP.length());
    }

    /**
     * The rule text that results from adding a group to an existing rule.
     *
     * Existing entries are kept and duplicates dropped, so picking two overlapping groups - raw
     * hides and prepared hides both mention hide - does not leave the same word in the list twice.
     * Case-insensitive, because the entries are matched that way and a rule reading "Bone, bone"
     * would be one entry that looks like two.
     */
    public static String add(String existing, String group) {
        List<String> out = new ArrayList<>();
        if (existing != null) {
            for (String part : existing.split(",")) {
                String t = part.trim();
                if (!t.isEmpty() && !containsIgnoreCase(out, t))
                    out.add(t);
            }
        }
        for (String item : GROUPS.getOrDefault(plain(group), Arrays.asList())) {
            if (!containsIgnoreCase(out, item))
                out.add(item);
        }
        return String.join(", ", out);
    }

    private static boolean containsIgnoreCase(List<String> list, String s) {
        for (String e : list) {
            if (e.equalsIgnoreCase(s))
                return true;
        }
        return false;
    }
}
