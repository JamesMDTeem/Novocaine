package haven.automated.lp;

import haven.Config;
import haven.automated.AUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The LP-discoverable world targets that aren't plants or rocks: ground forage, catchable critters
 * and huntable animals - plus the safety policy that decides which of them the auto-LP bot is
 * allowed to walk up to.
 *
 * Why this exists as its own file rather than more rows in LpSpec: LpSpec is bulk data ported
 * verbatim from nurgling2's VSpec, and its 247 entries were entirely trees, bushes, logs and
 * stones (plus four hand-added tokens: dandelion, standing grass, grasshopper, silkmoth, which
 * have moved here so there's one place that owns non-plant targets). Everything downstream keys off
 * LpSpec.object - Gob only attaches an LP marker when the map contains the resource, the minimap
 * renderer and LpPlanner both go through LpExplorer.allUndiscoveredProducts - so filling in this
 * table is all it takes to light up markers, minimap icons and discovery recording for a whole new
 * class of thing. The icon layer needed nothing: all 1063 productIcon entries were already there,
 * hides, tusks, antlers, mushrooms and all.
 *
 * The three groups differ in what the BOT may do with them, which is the important distinction:
 *
 *  - Forage and catchable critters are picked up by hand. The bot handles them through its normal
 *    "Pick" path, and processing (Clean/Butcher) is already covered by LpPlanner's inventory tasks.
 *
 *  - Huntable animals are MARKER-ONLY. Their products come out of a carcass, which means killing
 *    the animal first - not something a foraging bot should ever attempt. They're in HUNT_ONLY, and
 *    LpPlanner refuses to generate a task for anything in it. The marker still shows (with a ring,
 *    see Gob.updateLpAura) because "this deer still owes you a hide" is genuinely useful to know;
 *    it's just information for the player, not work for the bot.
 *
 * Resource names here were read out of the client's own resource cache rather than guessed from the
 * item-icon paths, so the slugs are the real ones (the two do usually agree - invobjs/herbs/blueberry
 * alongside terobjs/herbs/blueberry - but not always, e.g. the item "Sage" lives at .../salvia and
 * "Morels" at .../lorchel). A key that matches no live gob is inert: no marker, no task, no cost.
 */
public class LpTargets {

    /**
     * Resources whose LP products can only be had by killing the thing first. Never a bot target;
     * marker only. See the class comment.
     */
    private static final Set<String> huntOnly = new HashSet<>();

    /**
     * Products that more than one resource can yield - a Mallard Feather comes off both the drake
     * and the hen, Cleaned Chicken off any of three chicken gobs. Discovery for these has to be
     * checked GLOBALLY (LpLog.IsLpExplorerContainsAnywhere) rather than against one resource, or
     * every other resource sharing the name would keep its marker forever after the first pickup.
     * Exactly the reasoning bark already needed, generalised - and derived by counting declarations
     * during install() rather than hand-listed, so it can't drift out of sync with the data.
     */
    private static final Set<String> shared = new HashSet<>();

    // No initialiser, and declared ahead of the static block below on purpose: static field
    // initialisers run interleaved with static blocks in SOURCE order, so an `= false` sitting after
    // that block would run after install() had already set this true and silently un-set the guard.
    private static boolean installed;

    private LpTargets() {}

    /**
     * Makes sure the data is in place however this class is first reached.
     *
     * install() is driven from LpSpec's static initialiser, so anything that touches LpSpec gets the
     * full table. But the query methods below can be the FIRST thing anyone calls - LpPlanner asks
     * isNeverTarget before it asks LpExplorer anything - and initialising LpTargets does not by
     * itself initialise LpSpec, which would leave huntOnly empty and quietly report every animal as
     * a legal bot target. Touching LpSpec here closes that: whichever of the two is loaded first,
     * the other is initialised before any query can be answered. (Safe despite the cycle: this
     * class's field initialisers have already run by the time the static block does, so LpSpec's
     * call back into install() finds the sets it needs to fill.)
     */
    static {
        LpSpec.getIconPath(null);
    }

    // ------------------------------------------------------------------ data

    private static void put(String res, String... products) {
        ArrayList<String> list = new ArrayList<>(products.length);
        Collections.addAll(list, products);
        LpSpec.object.put(res, list);
    }

    private static void forage(String slug, String... products) {
        put("gfx/terobjs/herbs/" + slug, products);
    }

    private static void critter(String path, String... products) {
        put("gfx/kritter/" + path, products);
    }

    private static void hunt(String path, String... products) {
        String res = "gfx/kritter/" + path;
        put(res, products);
        huntOnly.add(res);
    }

    /**
     * Ground forage: herbs, flowers, mushrooms, shore pickups. All handled by a bare "Pick", all
     * harmless, all one product each. This is the group with the best ratio of value to risk -
     * pure data, nothing fights back, nothing gets destroyed.
     */
    private static void initForage() {
        forage("ambergris", "Ambergris");
        forage("baybolete", "Bay Bolete");
        forage("bladderwrack", "Washed-up Bladderwrack");
        forage("bloatedbolete", "Bloated Bolete");
        forage("bloodstern", "Blood Stern");
        forage("blueberry", "Blueberries");
        forage("camomile", "Camomile");
        forage("candleberry", "Canbelberry");
        forage("cavebulb", "Cavebulb");
        forage("chantrelle", "Chantrelles");
        forage("chimingbluebell", "Chiming Bluebell");
        forage("chives", "Chives");
        forage("clover", "Clover");
        forage("coltsfoot", "Coltsfoot");
        forage("commonstarfish", "Common Starfish");
        forage("dandelion", "Dandelion");
        forage("dill", "Dill");
        forage("duskfern", "Dusk Fern");
        forage("edelweiss", "Edelwei\u00df");
        forage("fieldblewit", "Field Blewits");
        forage("frogscrown", "Frog's Crown");
        forage("frogspawn", "Frogspawn");
        forage("ghostapple", "Ghost Apple");
        forage("giantpuffball", "Giant Puffball");
        forage("glimmermoss", "Glimmermoss");
        forage("goosebarnacle", "Gooseneck Barnacle");
        forage("greenkelp", "Green Kelp");
        forage("heartsease", "Heartsease");
        forage("kvann", "Kvann");
        forage("ladysmantle", "Lady's Mantle");
        forage("lakesnail", "Lake Snail");
        forage("lampstalk", "Lamp Stalk");
        forage("libertycap", "Liberty Caps");
        forage("lingon", "Lingonberries");
        forage("lorchel", "Morels");
        forage("lupine", "Lupine");
        forage("marshmallow", "Marsh-Mallow");
        forage("mistletoe", "Mistletoe");
        forage("mussels", "River Pearl Mussel");
        forage("oyster", "Oyster");
        forage("oystermushroom", "Oyster Mushroom");
        forage("parasolshroom", "Parasol Mushroom");
        forage("perfectautumnleaf", "Perfect Autumn Leaf");
        forage("rabbitfrost", "Rabbit Frost");
        forage("razorclams", "Razor Clam");
        forage("royaltoadstool", "Royal Toadstool");
        forage("rubybolete", "Ruby Bolete");
        forage("rustroot", "Rustroot");
        forage("salvia", "Sage");
        forage("sleighbell", "Sleighbell");
        forage("snapdragon", "Uncommon Snapdragon");
        forage("snowtop", "Snowtop");
        forage("spindlytaproot", "Spindly Taproot");
        forage("stalagoom", "Stalagoom");
        forage("standinggrass", "Standing Grass");
        forage("stingingnettle", "Stinging Nettle");
        forage("strawberry", "Strawberry");
        forage("tangledbramble", "Tangled Bramble");
        forage("tansy", "Tansy");
        forage("thornythistle", "Thorny Thistle");
        forage("thyme", "Thyme");
        forage("toadflax", "Toadflax");
        forage("waybroad", "Waybroad");
        forage("windweed", "Wild Windsown Weed");
        forage("wintergreen", "Wintergreen");
        forage("yarrow", "Yarrow");
        forage("yellowfoot", "Yellowfeet");
        forage("yulecracker", "Yule Cracker");
    }

    /**
     * Critters you pick up by hand, exactly like the grasshopper and silkmoth that were already
     * here. Product names are taken verbatim from LpSpec.category's own Bait/Bug/Snail/Shellfish
     * lists rather than invented, which is what makes the pickup register: the discovery hook
     * matches on the item's own name.
     *
     * Deliberately limited to critters whose picked-up item name is in that data. Several others in
     * the client's critter roster (crab, jellyfish, bog turtle, forest lizard) have no product name
     * anywhere in it, and guessing one would just produce a marker that can never be cleared.
     *
     * Many of these are curiosities whose LP really comes from STUDYING them. Picking one still
     * registers the discovery (the hook is possession, not the action), so they work as targets;
     * and if a bug's menu turns out to offer only Study, executeOnce already retires it quietly
     * instead of reporting a data gap.
     */
    private static void initCritters() {
        critter("bayshrimp/bayshrimp", "Bay Shrimp");
        critter("brimstonebutterfly/brimstonebutterfly", "Brimstone Butterfly");
        critter("cavecentipede/cavecentipede", "Cave Centipede");
        critter("cavemoth/cavemoth", "Cave Moth");
        critter("firefly/firefly", "Firefly");
        critter("forestsnail/forestsnail", "Forest Snail");
        critter("grasshopper/grasshopper", "Grasshopper");
        critter("ladybug/ladybug", "Ladybug");
        critter("lobster/lobster", "Lobster");
        critter("monarchbutterfly/monarchbutterfly", "Monarch Butterfly");
        critter("moonmoth/moonmoth", "Moonmoth");
        critter("sandflea/sandflea", "Sand Flea");
        critter("silkmoth/silkmoth", "Silkmoth", "Male Silkmoth");
        critter("springbumblebee/springbumblebee", "Springtime Bumblebee");
        critter("stagbeetle/stagbeetle", "Stag Beetle");
        critter("tick/tick", "Tick");
        critter("waterstrider/waterstrider", "Waterstrider");
        critter("woodworm/woodworm", "Woodworm");
        // Ground pickups that live under terobjs/items rather than kritter.
        put("gfx/terobjs/items/grub", "Grub");
        put("gfx/terobjs/items/itsybitsyspider", "Itsy Bitsy Spider");
    }

    /**
     * Animals whose LP comes out of a carcass. MARKER ONLY - see the class comment.
     *
     * Products are the species' own entries from LpSpec.category's Hide Fresh, Finebone, Bone
     * Material, Feather, Chitin and Clean*Carcass lists, cross-checked against the species' actual
     * resources (a boar has boar/boarhide and boar/boar-butcher; a walrus has walrus/walrushide and
     * the Walrus Tusk under Bone Material). Only names that exist in the item data are listed, so
     * every marker here is clearable.
     *
     * Livestock is deliberately absent (cattle, sheep, goat pens, pigs, horses, domestic chickens):
     * their gobs are shared with the wild forms - cattle/cattle is both a cow and an aurochs, told
     * apart only by composite layers - and marking a player's own herd would be noise, not news.
     */
    private static void initHuntable() {
        hunt("adder/adder", "Fresh Adder Hide", "Adder Skeleton", "Clean Adder Carcass");
        hunt("badger/badger", "Fresh Badger Hide");
        hunt("bat/bat", "Fresh Bat Hide", "Cleaned Bat");
        hunt("bear/bear", "Fresh Bear Hide", "Bear Tooth");
        hunt("beaver/beaver", "Fresh Beaver Hide");
        hunt("boar/boar", "Fresh Boarhide", "Boar Tusk");
        hunt("boreworm/boreworm", "Fresh Boreworm Hide", "Boreworm Beak");
        hunt("bullfinch/bullfinch", "Bullfinch Feather", "Cleaned Bullfinch");
        hunt("caveangler/caveangler", "Fresh Cave Angler Scales");
        hunt("cavelouse/cavelouse", "Cave Louse Chitin");
        hunt("eagleowl/eagleowl", "Eagle Owl Feather", "Cleaned Eagle Owl");
        hunt("fox/fox", "Fresh Fox Hide");
        hunt("goat/wildgoat", "Fresh Wildgoat Hide", "Wildgoat Horn");
        hunt("goldeneagle/goldeneagle", "Golden Eagle Feather", "Cleaned Golden Eagle");
        hunt("greyseal/greyseal", "Fresh Grey Seal Hide");
        hunt("hedgehog/hedgehog", "Fresh Hedgehog Skin", "Clean Hedgehog Carcass", "Dead Hedgehog");
        hunt("horse/horse", "Fresh Wildhorse Hide");
        hunt("lynx/lynx", "Fresh Lynx Hide", "Lynx Claws");
        hunt("magpie/magpie", "Magpie Feather", "Cleaned Magpie");
        hunt("mammoth/mammoth", "Fresh Mammoth Hide", "Mammoth Tusk");
        hunt("mole/mole", "Fresh Mole Hide", "Mole's Pawbone", "Clean Mole Carcass");
        hunt("moose/moose", "Fresh Moose Hide", "Moose Antlers");
        hunt("narwhal/narwhal", "Fresh Narwhal Hide");
        hunt("orca/orca", "Orca Tooth");
        hunt("otter/otter", "Fresh Otterhide");
        hunt("pelican/pelican", "Pelican Feather", "Cleaned Pelican");
        hunt("ptarmigan/ptarmigan", "Ptarmigan Feather", "Cleaned Ptarmigan");
        hunt("quail/quail", "Quail Feather", "Cleaned Quail");
        hunt("rabbit/rabbit", "Fresh Rabbit Fur", "Clean Rabbit Carcass");
        hunt("rat/caverat", "Fresh Caverat Hide");
        hunt("reddeer/reddeer", "Fresh Red Deer Hide", "Red Deer Antlers");
        hunt("reindeer/reindeer", "Fresh Reindeer Hide", "Reindeer Antlers");
        hunt("rockdove/rockdove", "Rock Dove Feather", "Cleaned Rock Dove");
        hunt("roedeer/roedeer", "Fresh Roe Deer Hide", "Roe Deer Antlers");
        hunt("seagull/seagull", "Seagull Feather", "Cleaned Seagull");
        hunt("sheep/mouflon", "Fresh Mouflon Hide");
        hunt("spermwhale/spermwhale", "Cachalot Tooth");
        hunt("squirrel/squirrel", "Fresh Squirrel Hide", "Fresh Squirrel Tail", "Clean Squirrel Carcass");
        hunt("stoat/stoat", "Fresh Stoat Hide", "Clean Stoat Carcass", "Dead Stoat");
        hunt("swan/swan", "Swan Feather", "Cleaned Swan");
        hunt("troll/troll", "Fresh Troll Hide", "Troll Skull", "Troll Tusks", "Trollbone");
        hunt("walrus/walrus", "Fresh Walrus Hide", "Walrus Tusk");
        hunt("wolf/wolf", "Fresh Wolf Hide", "Wolf's Claw");
        hunt("wolverine/wolverine", "Fresh Wolverine Hide");
        // Species that appear in the world under several resource names - by sex, by age, or by
        // season - all yielding the same products. Listing each one is what makes its gobs get a
        // marker; the overlapping product names are what the `shared` set above exists to handle, so
        // finding a Mallard Feather off the drake clears the hen's marker too.
        hunt("mallard/mallard", "Mallard Feather", "Cleaned Mallard");
        hunt("mallard/mallard-m", "Mallard Feather", "Cleaned Mallard");
        hunt("mallard/mallard-f", "Mallard Feather", "Cleaned Mallard");
        hunt("woodgrouse/woodgrouse-m", "Wood Grouse Feather", "Cleaned Wood Grouse Cock");
        hunt("woodgrouse/woodgrouse-f", "Wood Grouse Feather", "Cleaned Wood Grouse Hen");
        hunt("chicken/chicken", "Chicken Feathers", "Cleaned Chicken", "Dead Cock", "Dead Hen");
        hunt("chicken/hen", "Chicken Feathers", "Cleaned Chicken", "Dead Hen");
        hunt("chicken/rooster", "Chicken Feathers", "Cleaned Chicken", "Dead Cock");
        hunt("rabbit/bunny", "Fresh Rabbit Fur", "Clean Rabbit Carcass");
        // The winter coat is its own resource and its own hide item (stoat/stoathide-winter).
        hunt("stoat/stoat-winter", "Fresh Ermine", "Clean Stoat Carcass", "Dead Stoat");
    }

    /**
     * Fills in LpSpec.object and works out which product names are shared. Called from LpSpec's own
     * static initialiser, after its tables are populated, so LpSpec stays the single map everything
     * downstream reads.
     */
    static synchronized void install() {
        if (installed)
            return;
        installed = true;
        initForage();
        initCritters();
        initHuntable();

        // Count declarations across the WHOLE table, not just the rows added here, so a name shared
        // with something already in LpSpec (or added upstream later) is caught too.
        Map<String, Integer> counts = new HashMap<>();
        for (ArrayList<String> products : LpSpec.object.values()) {
            for (String product : products)
                counts.merge(product, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > 1)
                shared.add(e.getKey());
        }
    }

    // ------------------------------------------------------------------ queries

    /** Whether discovery of this product must be checked globally rather than per-resource. */
    public static boolean isSharedProduct(String product) {
        return shared.contains(product);
    }

    /** A resource whose products need the animal dead - marker only, never a bot task. */
    public static boolean isHuntOnly(String res) {
        return res != null && huntOnly.contains(res);
    }

    /**
     * How far a dangerous beast's aggro reaches, in map units. Mirrors the 120f circle the client
     * already draws around them (Gob.updateDangerousBeastRadii), so the number the bot avoids and
     * the circle the player sees are the same thing.
     */
    public static final double DANGER_RADIUS = 120.0;

    /**
     * How much clearance the bot keeps from a dangerous beast: the full DIAMETER of that circle, so
     * a target sitting just outside the ring still isn't approached. Walking to the edge of a bear's
     * aggro range and then swinging an axe for thirty seconds is how you get killed by a bear -
     * treating the radius as if it were twice as big is cheap insurance, and the only cost is
     * skipping targets that happen to sit near one.
     */
    public static final double DANGER_KEEPOUT = 2 * DANGER_RADIUS;

    /**
     * How wide a berth the bot's PATHING gives a beast - the aggro circle plus a quarter, rather
     * than the doubled DANGER_KEEPOUT that target selection uses.
     *
     * The two numbers answer different questions. DANGER_KEEPOUT asks "may I stand here for thirty
     * seconds swinging an axe while that thing wanders?", and doubling the radius is cheap there
     * because the only cost is skipping a target. This one asks "may I walk past at range?", where
     * the same doubling would wall off a 480-unit-wide disc of ground - routinely every route to
     * everything - and turn a detour back into the refusal it replaced.
     */
    public static final double DANGER_PATH_CLEARANCE = 1.25 * DANGER_RADIUS;

    /**
     * One of the beasts that will chase and kill you - the same roster the client draws a red radius
     * circle for. Matched by suffix, as Gob.updateDangerousBeastRadii does, so variants resolve the
     * same way there and here.
     */
    public static boolean isDangerous(String res) {
        // Every beast on the roster lives under gfx/kritter, so this one comparison rejects the
        // trees, bushes and boulders that make up nearly every gob on screen before the 25-entry
        // suffix scan below - which runs for every loaded gob, a couple of times a second, while
        // the bot is approaching something.
        if (res == null || !res.startsWith("gfx/kritter/"))
            return false;
        for (String beast : Config.beastResPaths) {
            if (res.endsWith(beast))
                return true;
        }
        return false;
    }

    /**
     * Anything the bot must not walk up to, whatever it might yield: the dangerous beasts above,
     * Hurricane's wider "will fight back if provoked" roster, and every hunt-only animal. Three
     * overlapping lists on purpose - each covers cases the others miss, and the cost of an extra
     * entry is one skipped target while the cost of a miss is a dead character.
     */
    public static boolean isNeverTarget(String res) {
        if (res == null)
            return true;
        return isHuntOnly(res) || isDangerous(res) || AUtils.potentialAggroTargets.contains(res);
    }
}
