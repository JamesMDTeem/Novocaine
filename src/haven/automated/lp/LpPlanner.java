package haven.automated.lp;

import haven.GameUI;
import haven.Gob;
import haven.Loading;
import haven.OCache;
import haven.WItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Works out which currently-reachable actions would yield an LP product the character hasn't
 * discovered yet, and in what order they're worth doing.
 *
 * Split out from the bot that executes them so the same enumeration can answer "is there anything
 * worth doing near me" without moving the character - e.g. for a HUD counter, or to decide whether
 * the bot has anything left to do at all.
 */
public class LpPlanner {

    // Tools, by item name. A task with a tool it can't equip is skipped rather than attempted.
    public static final LpAlias AXE = new LpAlias(
        "Woodsman's Axe", "Stone Axe", "Metal Axe",
        "Butcher's cleaver", "Tinker's Throwing Axe", "Battle Axe of the Twelfth Bay");
    public static final LpAlias SAW = new LpAlias("Bonesaw", "Metal Saw");
    public static final LpAlias PICKAXE = new LpAlias(
        "Pickaxe", "Woodsman's Axe", "Stone Axe", "Metal Axe");

    // Candidate flower options per gob kind, cheapest/least destructive first. These strings are
    // known-good against the live menu (the same ones nurgling2's single-purpose bots used).
    // The seed/fruit/berry flower option is SPECIES-SPECIFIC and can't be a fixed string - the
    // live menu offers "Pick cone", "Pick berries", "Pick fruits", "Pick drupes", "Pick hips",
    // "Pick catkin(s)" and so on, one per species. This sentinel tells the executor to resolve it
    // against the open menu: take whatever "Pick <x>" it offers that isn't a different category.
    // Ground forageables (herbs, mushrooms) instead use the bare "Pick", which that same resolver
    // also accepts.
    public static final String SEED_PICK = " seedpick";
    private static final List<String> SEED_OPTIONS = Collections.unmodifiableList(
        Arrays.asList(SEED_PICK));
    // Kept as two separate one-option task types rather than one two-option task, because they
    // need DIFFERENT tools - boards want a saw, blocks want an axe - and a task carries a single
    // tool requirement. Both are offered for the same log; whichever tool the player actually has
    // is the one that runs.
    private static final List<String> MAKE_BOARDS = Collections.unmodifiableList(
        Arrays.asList("Make boards"));
    private static final List<String> CHOP_BLOCKS = Collections.unmodifiableList(
        Arrays.asList("Chop into blocks"));
    private static final List<String> STONE_PROCESS = Collections.unmodifiableList(
        Arrays.asList("Chip stone"));
    private static final List<String> FELL = Collections.unmodifiableList(
        Arrays.asList("Chop"));
    // Carcass/critter processing, in the order the game itself applies them.
    private static final List<String> CARCASS = Collections.unmodifiableList(
        Arrays.asList("Skin", "Scale", "Crack", "Clean", "Butcher", "Collect bones"));

    private LpPlanner() {}

    /**
     * Every LP-yielding action currently visible, felling last and nearest-first otherwise.
     * `exhausted` holds LpTask.key() values already tried without result, so a target that
     * turned out to yield nothing new isn't retried forever.
     */
    public static List<LpTask> plan(GameUI gui, double radius, Set<String> exhausted)
            throws InterruptedException {
        List<LpTask> tasks = new ArrayList<>();
        if (!LpExplorer.isEnabled() || gui == null || gui.map == null || gui.map.player() == null)
            return tasks;

        collectInventoryTasks(gui, tasks);
        collectWorldTasks(gui, radius, tasks);

        tasks.removeIf(t -> {
            for (String opt : t.options) {
                if (!exhausted.contains(t.key(opt)))
                    return false;
            }
            return true;  // every option for this target already tried and spent
        });

        haven.Coord2d me = gui.map.player().rc;
        tasks.sort((a, b) -> {
            // Only FELLING (destructive) is deprioritised as a class - everything gentler is done
            // first, so a tree is never chopped while there's anything to pick/mine/process nearby.
            // Within the gentler group, go to whatever's NEAREST rather than following a fixed
            // harvest-before-mine-before-process tier order, which made the bot march past a close
            // rock to reach a far bush just because harvest outranked mine. Inventory items sort
            // first naturally (their distance is 0), which is right - they cost no travel.
            int ga = a.tier == LpTask.TIER_FELL ? 1 : 0;
            int gb = b.tier == LpTask.TIER_FELL ? 1 : 0;
            if (ga != gb)
                return Integer.compare(ga, gb);
            double da = a.isItem() ? 0 : a.gob.rc.dist(me);
            double db = b.isItem() ? 0 : b.gob.rc.dist(me);
            return Double.compare(da, db);
        });
        return tasks;
    }

    /**
     * The harvest options worth offering for one tree/bush, restricted to categories that are
     * ACTUALLY still undiscovered for this species.
     *
     * This restriction is the whole point. Offering a fixed list and letting the executor take
     * whichever the menu happened to have meant "Take bark" - first in the list and available on
     * every mature tree - got picked on tree after tree long after bark was discovered, because
     * the tree still had an undiscovered leaf or seed and so still generated a task. Most species
     * share the same bark item ("Treebark", or "Tough Bark" for the BARKS_MAP group), so one
     * pickup satisfies nearly all of them at once; asking per species was pure wasted walking.
     */
    public static List<String> harvestOptions(String gobResName, boolean isTree) {
        List<String> opts = new ArrayList<>();
        if (isTree && LpExplorer.hasUndiscoveredBarkProduct(gobResName))
            opts.add("Take bark");
        LpExplorer.UndiscoveredCategories cats = LpExplorer.undiscoveredCategories(gobResName);
        if (cats.bough) {
            // Most species offer "Take bough"; a few (olive) offer "Take branch" for the same
            // category. Both are candidates - the executor takes whichever the live menu has.
            opts.add("Take bough");
            opts.add("Take branch");
        }
        if (cats.leaf)
            opts.add("Pick leaf");
        if (cats.seed)
            opts.addAll(SEED_OPTIONS);
        return opts;
    }

    private static void collectWorldTasks(GameUI gui, double radius, List<LpTask> out)
            throws InterruptedException {
        haven.Coord2d me = gui.map.player().rc;
        long playerId = gui.map.plgob;

        // Snapshot under the lock, evaluate outside it - the discovery scan below can block on
        // icon/sprite loads.
        List<Gob> snapshot = new ArrayList<>();
        OCache oc = gui.ui.sess.glob.oc;
        synchronized (oc) {
            for (Gob gob : oc)
                snapshot.add(gob);
        }

        // Where the dangerous beasts are, so targets near one can be left alone entirely. Computed
        // once for the whole scan rather than per candidate.
        List<Gob> hazards = hazards(snapshot);

        for (Gob gob : snapshot) {
            if (gob.id == playerId)
                continue;
            if (gob.rc.dist(me) > radius)
                continue;
            try {
                String res = LpExplorer.resname(gob);
                // The bot must never walk up to something that fights back, or to something whose
                // products need it dead - whatever LP it might be holding. See LpTargets.
                if (res == null || LpTargets.isNeverTarget(res))
                    continue;
                if (nearHazard(gob, hazards))
                    continue;
                // Things standing in open water (waterstriders, the odd shore forage that spawned
                // a tile out) can't be walked to at all. Without this the bot picked one as its
                // nearest target, the pathfinder returned the closest it could manage - the shore -
                // and the approach loop then burned its whole attempt budget re-pathing at a gob it
                // was never going to reach, on repeat, for as long as the critter existed.
                if (onWater(gui, gob))
                    continue;

                List<String> undiscovered = LpExplorer.allUndiscoveredProducts(gob);
                if (!undiscovered.isEmpty()) {
                    String why = undiscovered.get(0);
                    // Old trunks go with logs, not stones: they yield a Block, so they want the
                    // wood-processing options and an axe - not "Chip stone" and a pickaxe.
                    if (HarvestSpecs.LOG.matches(res) || HarvestSpecs.OLDTRUNK.matches(res)) {
                        // Offer each processing action only while ITS OWN product is still
                        // undiscovered. Sawing discovers the Board but never the Block (and chopping
                        // vice versa), so a log that still has an undiscovered Block would otherwise
                        // keep regenerating the Make-boards task forever - the bot sawing board
                        // after board, dropping each, never reaching the block. Split by which
                        // product each yields (every board is "Board of X", every block "Block of
                        // X") so each action runs exactly until its own product is found. Fallback:
                        // if the undiscovered product is neither (shouldn't happen for a log), offer
                        // both rather than nothing, so the log can't get stuck perpetually green.
                        boolean boardUndisc = false, blockUndisc = false;
                        for (String p : undiscovered) {
                            if (p.contains("Board")) boardUndisc = true;
                            else if (p.contains("Block")) blockUndisc = true;
                        }
                        if (!boardUndisc && !blockUndisc)
                            boardUndisc = blockUndisc = true;
                        if (boardUndisc)
                            out.add(LpTask.onGob(gob, MAKE_BOARDS, SAW, LpTask.TIER_PROCESS, why));
                        if (blockUndisc)
                            out.add(LpTask.onGob(gob, CHOP_BLOCKS, AXE, LpTask.TIER_PROCESS, why));
                    }
                    else if (HarvestSpecs.STONE.matches(res))
                        out.add(LpTask.onGob(gob, STONE_PROCESS, PICKAXE, LpTask.TIER_MINE, why));
                    else if (HarvestSpecs.BUSH.matches(res) || HarvestSpecs.TREE.matches(res)) {
                        List<String> opts = harvestOptions(res, HarvestSpecs.TREE.matches(res));
                        if (!opts.isEmpty())
                            out.add(LpTask.onGob(gob, opts, null, LpTask.TIER_HARVEST, why));
                    }
                    else
                        // Ground forage (herbs, mushrooms, kritters) - no HarvestSpec covers
                        // these, and the bare "Pick" is what foraging uses for them.
                        out.add(LpTask.onGob(gob, Collections.singletonList("Pick"), null,
                            LpTask.TIER_HARVEST, why));
                }

                // Felling a standing tree for its wood LP. Off by default (autolpCutTrees) because
                // it's destructive and, worse, self-perpetuating: felling doesn't itself discover
                // the log's boards/blocks - you must then process the log - so every standing tree
                // of the species keeps qualifying and the bot would clear-cut the whole stand.
                // Gated twice: the toggle, AND ownProductsAllDiscovered so a tree whose own
                // berries/seed/etc aren't found yet is never chopped down (which is exactly what
                // happened to a juniper's berries before this).
                if (LpConfig.on(LpConfig.Key.autolpCutTrees)
                        && LpExplorer.hasUndiscoveredDerivedProduct(res)
                        && LpExplorer.ownProductsAllDiscovered(res)) {
                    String derived = LpExplorer.derivedResource(res);
                    out.add(LpTask.onGob(gob, FELL, AXE, LpTask.TIER_FELL, "wood of " + derived));
                }
            } catch (Loading l) {
                // Sprite not ready this pass; it'll be reconsidered on the next plan().
            }
        }
    }

    /**
     * The dangerous beasts currently loaded - bears, wolves, trolls and the rest of the roster the
     * client draws a red aggro circle around. Knocked/dead ones are skipped, matching the client's
     * own behaviour of dropping the circle once a beast is down.
     */
    public static List<Gob> hazards(List<Gob> gobs) {
        List<Gob> out = new ArrayList<>();
        for (Gob gob : gobs) {
            String res = LpExplorer.resname(gob);
            if (res == null || !LpTargets.isDangerous(res))
                continue;
            if (Boolean.TRUE.equals(gob.knocked))
                continue;
            out.add(gob);
        }
        return out;
    }

    /**
     * Whether a candidate target sits close enough to a dangerous beast that going for it isn't
     * worth it. The margin is the full DIAMETER of the beast's aggro circle rather than its radius:
     * an LP action isn't instantaneous - the character stands there picking or chopping, and the
     * beast is free to wander - so stopping at the edge of the ring is stopping too close. See
     * LpTargets.DANGER_KEEPOUT.
     */
    public static boolean nearHazard(Gob target, List<Gob> hazards) {
        for (Gob beast : hazards) {
            if (target.rc.dist(beast.rc) < LpTargets.DANGER_KEEPOUT)
                return true;
        }
        return false;
    }

    /** The nearest dangerous beast inside its keep-out margin of a point, or null. */
    public static Gob hazardNear(GameUI gui, haven.Coord2d c) {
        return hazardWithin(gui, c, LpTargets.DANGER_KEEPOUT);
    }

    /** The nearest dangerous beast within `margin` of a point, or null. */
    public static Gob hazardWithin(GameUI gui, haven.Coord2d c, double margin) {
        Gob nearest = null;
        double best = margin;
        OCache oc = gui.ui.sess.glob.oc;
        synchronized (oc) {
            for (Gob gob : oc) {
                String res = LpExplorer.resname(gob);
                if (res == null || !LpTargets.isDangerous(res) || Boolean.TRUE.equals(gob.knocked))
                    continue;
                double d = gob.rc.dist(c);
                if (d < best) {
                    best = d;
                    nearest = gob;
                }
            }
        }
        return nearest;
    }

    /**
     * The beasts' aggro rings expressed as pathfinder keep-out circles, so a route can be planned
     * AROUND them instead of the approach simply being abandoned.
     *
     * Any circle the character is already inside is left out: the pathfinder's own starting cell
     * would be inside a blocked region and no route out of it would exist. Getting clear of one is
     * the bot's job (AutoLpBot.retreatFrom), and once it has, the ring reappears here.
     *
     * The radius used is DANGER_PATH_CLEARANCE, not the bigger DANGER_KEEPOUT that target selection
     * uses - walking past a bear at range is a different proposition from standing next to one
     * swinging an axe for half a minute, and pathing with the larger figure walls off far more
     * ground than the risk warrants.
     */
    public static haven.automated.pathfinder.Map.Keepout[] keepouts(GameUI gui, haven.Coord2d from) {
        List<haven.automated.pathfinder.Map.Keepout> out = new ArrayList<>();
        OCache oc = gui.ui.sess.glob.oc;
        synchronized (oc) {
            for (Gob gob : oc) {
                String res = LpExplorer.resname(gob);
                if (res == null || !LpTargets.isDangerous(res) || Boolean.TRUE.equals(gob.knocked))
                    continue;
                if (gob.rc.dist(from) <= LpTargets.DANGER_PATH_CLEARANCE)
                    continue;
                out.add(new haven.automated.pathfinder.Map.Keepout(
                    gob.rc, LpTargets.DANGER_PATH_CLEARANCE));
            }
        }
        return out.toArray(new haven.automated.pathfinder.Map.Keepout[0]);
    }

    /**
     * Whether a gob is standing on water the character can't walk onto.
     *
     * Checked per candidate rather than left to the pathfinder because the two failures are
     * different: the pathfinder would route to the nearest reachable point and report success,
     * leaving the bot right-clicking at a critter bobbing about out of reach. Ruling the target out
     * up front turns that into "pick something else".
     */
    private static boolean onWater(GameUI gui, Gob gob) {
        try {
            haven.MCache mc = gui.ui.sess.glob.map;
            haven.Resource res = mc.tilesetr(mc.gettile(gob.rc.floor(haven.MCache.tilesz)));
            return res != null && haven.automated.pathfinder.Map.isWater(res.name);
        } catch (Loading l) {
            // Grid not in yet - judge it on the next plan() rather than rejecting it blind.
            return false;
        }
    }

    // Item categories whose members are worth trying a carcass-processing option on. Taken from
    // LpSpec.category, which already curates these by exact item name, rather than a guessed
    // species list.
    //
    // Restricting by category matters for safety, not just tidiness: executing an inventory task
    // activates the item, and activating an arbitrary item can DO something - the flower menu
    // auto-picks when the player has a matching entry configured in their auto-select list.
    // Offering every item in the pack would mean activating food, stacks and tools on the off
    // chance they were butcherable. Deliberately conservative: an unlisted critter yields no task
    // rather than a blind activation, and widening it is a matter of adding a category here.
    // Deliberately excludes "Bug": that category is curiosities (Ladybug, butterflies, beetles,
    // ...) whose LP comes from STUDYING them at a study desk, not from a carcass-processing menu
    // option - offering them a Skin/Butcher task just made the bot right-click a Ladybug that only
    // offers "Study" and log a spurious "no known option". Picking a bug already registers it if
    // it's a tracked world resource; studying it is a manual, out-of-scope action for this bot.
    private static final String[] PROCESSABLE_CATEGORIES = {
        "Dead Animal Carcass", "Clean Animal Carcass", "Clean Bird Carcass",
        "Snail", "Shellfish", "Fish",
    };

    private static Set<String> processableNames;

    /**
     * Whether an item by this name is one the bot wants to KEEP because it can be processed for LP
     * (a carcass to skin, a critter to clean). Used by the bot's inventory tidy-up to spare these
     * from the auto-drop that clears out already-harvested products - dropping a carcass would throw
     * away a pending processing task. Everything not processable (and not a tool) is fair game to
     * drop, since LP only needs a product's name to have been SEEN, not kept.
     */
    public static boolean isProcessable(String name) {
        return name != null && processableNames().contains(name);
    }

    private static synchronized Set<String> processableNames() {
        if (processableNames == null) {
            Set<String> names = new HashSet<>();
            for (String category : PROCESSABLE_CATEGORIES)
                names.addAll(LpSpec.getCategoryContent(category));
            processableNames = names;
        }
        return processableNames;
    }

    /**
     * Inventory items worth acting on - carcasses to skin, critters to clean, and so on.
     *
     * Unlike world gobs there's no data table saying which item yields which product (LpSpec.object
     * is keyed by gob resource, not by item), so this can't know in advance that butchering a
     * given carcass would reveal something new. It offers the whole processing chain for anything
     * in a processable category and lets the executor's exhausted-set prune what turns out to
     * yield nothing - cheap, since these cost no travel at all.
     */
    private static void collectInventoryTasks(GameUI gui, List<LpTask> out)
            throws InterruptedException {
        if (gui.maininv == null)
            return;
        Set<String> processable = processableNames();
        List<WItem> items;
        synchronized (gui.maininv.wmap) {
            items = new ArrayList<>(gui.maininv.wmap.values());
        }
        for (WItem wi : items) {
            String name = wi.item.getname();
            if (name == null || !processable.contains(name))
                continue;
            out.add(LpTask.onItem(wi, CARCASS, null, name));
        }
    }
}
