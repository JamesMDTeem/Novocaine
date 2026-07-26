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
 */
public class ItemGroups {
    /** Group name -> the item-name fragments it stands for, in the order they are written out. */
    public static final Map<String, List<String>> GROUPS = new LinkedHashMap<>();

    private static void group(String name, String... items) {
        GROUPS.put(name, Arrays.asList(items));
    }

    static {
        group("Prepared hides", "Leather", "Scraped Hide", "Cured Hide", "Tanned Hide");
        group("Raw hides", "Hide", "Pelt", "Fur");
        group("Stone and ore", "Stone", "Boulder", "Ore", "Cinnabar", "Nugget");
        group("Timber", "Log", "Board", "Block", "Branch", "Bough");
        group("Bars and metal", "Bar", "Ingot", "Wrought Iron", "Steel");
        group("Fibre and cloth", "Fibre", "Fiber", "Yarn", "Thread", "Cloth", "Linen", "Hemp");
        group("Bones and horn", "Bone", "Antler", "Horn", "Tusk", "Hoof");
        group("Clay and bricks", "Clay", "Brick", "Tile");
        group("Seeds", "Seed", "Grain", "Barley", "Wheat", "Flax", "Millet", "Carrot", "Turnip");
        group("Cooked food", "Roast", "Stew", "Soup", "Pie", "Bread", "Porridge", "Sausage");
        group("Raw meat", "Meat", "Fillet", "Chop", "Sausage Meat", "Offal");
        group("Water containers", "Waterskin", "Bottle", "Bucket", "Flask", "Kuksa");
        group("Tools", "Axe", "Pickaxe", "Shovel", "Hammer", "Saw", "Knife", "Sickle", "Scythe");
        group("Curiosities", "Curiosity");
    }

    private ItemGroups() {}

    public static List<String> names() {
        return new ArrayList<>(GROUPS.keySet());
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
        for (String item : GROUPS.getOrDefault(group, Arrays.asList())) {
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
