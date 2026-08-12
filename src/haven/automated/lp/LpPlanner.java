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
     * What a plan() call decided, returned as a value rather than passed out through parameters.
     */
    public static class PlanResult {
        /** The tasks to execute, felling last and nearest-first otherwise. */
        public final List<LpTask> tasks;
        /** How many targets were dropped for standing inside a wall we are not inside - so a caller
         *  that ends up with an empty list can say WHY it is empty instead of just reporting
         *  "finished". */
        public final int walledOff;

        PlanResult(List<LpTask> tasks, int walledOff) {
            this.tasks = tasks;
            this.walledOff = walledOff;
        }
    }

    /**
     * Every LP-yielding action currently visible, felling last and nearest-first otherwise.
     * `exhausted` holds LpTask.key() values already tried without result, so a target that
     * turned out to yield nothing new isn't retried forever. It is read-only input: plan() only
     * filters against it. Only the executor can add to it, at click time, when an option is
     * actually tried and found spent - so the set is owned by the caller, not by the planner.
     */
    public static PlanResult plan(GameUI gui, double radius, Set<String> exhausted)
            throws InterruptedException {
        return plan(gui, radius, exhausted, null);
    }

    /**
     * @param ownGear items the character was already carrying when the run began, which are the
     *                player's own and not the bot's work. {@code tidyInventory} has always drawn
     *                that line and refuses to drop them; planning did not, so a Forest Snail in the
     *                pack at login became a task to Skin, Butcher and Collect bones from. It has no
     *                flower menu where it sits, so the bot right-clicked it, waited, retried three
     *                times and retired it - at the head of the list, so it cost the first three
     *                plans and about three seconds of every single run.
     */
    public static PlanResult plan(GameUI gui, double radius, Set<String> exhausted,
                                  Set<haven.GItem> ownGear)
            throws InterruptedException {
        List<LpTask> tasks = new ArrayList<>();
        if (!LpExplorer.isEnabled() || gui == null || gui.map == null || gui.map.player() == null)
            return new PlanResult(tasks, 0);

        collectInventoryTasks(gui, tasks, ownGear);
        collectWorldTasks(gui, radius, tasks);

        tasks.removeIf(t -> {
            for (String opt : t.options) {
                if (!exhausted.contains(t.key(opt)))
                    return false;
            }
            return true;  // every option for this target already tried and spent
        });

        /* Anything inside somebody's wall leaves the list HERE, rather than being discovered one
         * per plan at the head of it.
         *
         * This is not tidiness, it is the fix for a sixteen-second freeze. {@link #rerankByWalk}
         * measures the real walking distance to the leading candidates, and a walled-off target is
         * the worst possible input to that: the search cannot reach it, so it exhausts every tile
         * it CAN reach - up to Router.MAX_TILES - before returning "no". Eight of those at the head
         * of a plan is the bot standing still for the better part of a minute, which is exactly
         * what the log shows (18s between an action and the next plan, 16s between a skip and the
         * next, both while walled-off targets held the head of the list).
         *
         * It also ends the churn those targets caused: each one used to cost a whole re-plan of
         * 150+ tasks to reject a single gob, and the plan numbers walked up one target at a time
         * doing nothing else.
         *
         * Cheap enough to run over the WHOLE list, unlike the reachability search the executor
         * keeps for the one target it is about to walk to: this is a tile lookup against the
         * remembered barriers, not a route. */
        int walled = 0;
        for (java.util.Iterator<LpTask> it = tasks.iterator(); it.hasNext(); ) {
            LpTask t = it.next();
            if (t.isItem() || (t.gob == null))
                continue;
            try {
                if (haven.automated.nbots.world.Barriers.walledOffFrom(gui, t.gob.rc)) {
                    it.remove();
                    walled++;
                }
            } catch (RuntimeException e) {
                // Includes Loading: a half-loaded map says nothing either way, so leave the target
                // in and let the executor's own check decide once it can be answered.
            }
        }

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
        rerankByWalk(gui, tasks);
        return new PlanResult(tasks, walled);
    }

    /** How many of the nearest candidates get their real walking distance measured. */
    private static final int RERANK = 8;

    /**
     * How far from a target to look for somewhere to stand, passed to the router.
     *
     * The same figure {@code AutoLpBot.REACH_MARGIN} passes to {@code Router.reachable}, and it must
     * stay the same: the two questions are "can I get to it" and "how far is getting to it", and
     * they have to be asked about the same spot or a target can be accepted by one and ranked by a
     * walk to somewhere else. Note the router divides it by the tile size, so this is world units.
     *
     * The MARGIN is shared; how many standing spots inside it each question tries is not, and since
     * 2026-08-06 they differ on purpose - {@code Router.reachable} walks the whole first two rings,
     * {@code Router.walkingDistance} keeps to the nearest four. See the note on {@code Router.NEAR}.
     * The consequence to know about here: a target reachable only from the outer ring is accepted
     * but comes back with no measured distance, so it is ranked by its straight line. It is ranked
     * optimistically, which costs a place in the ordering - it is not dropped.
     */
    private static final int STAND_MARGIN = 50;

    /**
     * Re-orders the leading candidates by how far the bot would actually WALK to each.
     *
     * The sort above measures the straight line, which is the distance to a target only when
     * nothing is in the way. Across a stream, round a palisade, or the far side of a cliff, the
     * nearest-LOOKING thing is routinely the dearest one to get to - and the bot marches at it,
     * spends its attempt budget, gives up, and comes back to it next plan because it still looks
     * nearest. That is the "not pathing to the closest valid object" the logs kept showing.
     *
     * Only the leading few, because each one costs a search. They are the only ones that can win
     * anyway: a route is never shorter than the straight line, so a candidate lying further off in
     * a straight line than the best measured WALK cannot beat it, and everything past the head of
     * this list is further off than everything in it.
     *
     * A candidate whose walk cannot be measured - beyond observed ground, another segment - keeps
     * its straight-line distance, which is the honest lower bound and exactly what it was ranked by
     * before. Unmeasurable must not mean unattractive; that would demote everything past the
     * observed edge on no evidence.
     */
    private static void rerankByWalk(GameUI gui, List<LpTask> tasks) {
        int end = 0;
        // Stop at the felling boundary as well as at RERANK: felling is deprioritised as a class,
        // and re-ordering across that line would undo it.
        while ((end < tasks.size()) && (end < RERANK) && (tasks.get(end).tier != LpTask.TIER_FELL))
            end++;
        if (end < 2)
            return;
        haven.Coord2d me = gui.map.player().rc;
        java.util.Map<LpTask, Double> walk = new java.util.IdentityHashMap<>();
        for (LpTask t : tasks.subList(0, end)) {
            if (t.isItem()) {
                walk.put(t, 0.0);
                continue;
            }
            /* Near enough to be standing at it: no route to measure, and no search worth spending.
             * A straight line this short cannot be hiding a detour - the margin is the distance we
             * are willing to end up at anyway - so the walk is zero and it sorts to the front,
             * which is where a target we are already touching belongs. Saves a full A* per task on
             * the commonest case there is: the second and third jobs on the log under our feet. */
            double line = t.gob.rc.dist(me);
            if (line <= STAND_MARGIN) {
                walk.put(t, 0.0);
                continue;
            }
            double d = haven.automated.nbots.world.Router.walkingDistance(gui, t.gob.rc, STAND_MARGIN);
            walk.put(t, (d < 0) ? line : d);
        }
        List<LpTask> head = new ArrayList<>(tasks.subList(0, end));
        head.sort((a, b) -> Double.compare(walk.get(a), walk.get(b)));
        for (int i = 0; i < end; i++)
            tasks.set(i, head.get(i));
    }

    /* The species-level harvestOptions(String, boolean) stood here and is DELETED, not merely
     * unused. It answered "what might a tree of this kind still have" and there is no caller left
     * for whom that is the right question: the planner and the executor both want THIS gob.
     *
     * Left in place it was a live trap rather than dead weight. It sat directly above the per-gob
     * overload with a nearly identical signature and a confident javadoc, and the executor picked
     * it - so the bot correctly stopped WALKING to picked-clean bushes while still asking them for
     * things once it arrived, which reads in the log as the planner being wrong when it was right.
     * Its restriction-to-undiscovered-categories reasoning is not lost; the per-gob overload
     * applies the same restriction, from the gob's own bitmask rather than from the species. */

    /**
     * Species+option pairs the LIVE flower menu has been seen not to offer.
     *
     * A data gap we can close by watching. LpSpec says a juniper has Tough Bark and
     * {@link LpExplorer#allUndiscoveredProducts} passes bark through ungated - bark is the one
     * category with no live bit, on the assumption that any mature tree has some. Junipers
     * disprove it: their menu offers Chop, Take branch and Pick berries, never Take bark. Seven
     * "no known option" lines in one run were that assumption meeting the game.
     *
     * We cannot know it from the resource, but the menu tells us the moment we look, and it is
     * a fact about the SPECIES rather than the individual - so one look answers it for every
     * juniper for the rest of the session.
     *
     * Session-scoped on purpose, not persisted: it is inferred from one menu rather than looked
     * up, and a wrong entry that outlived the session would quietly stop harvesting something
     * real. Concurrent because the planner reads it while the bot thread writes it.
     */
    private static final java.util.Map<String, Set<Long>> menuLacks =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * How many DIFFERENT gobs of a species must lack an option before we believe it of the species.
     *
     * One was not evidence, and treating it as evidence is the bug this constant exists to fix.
     * The observation is per-object: a tree whose bark has already been taken offers no "Take bark"
     * either, and generalising that stops the bot trying bark on every other tree of the species -
     * including the ones that still have it. The juniper case this mechanism was built for was read
     * from one grove across three sessions, which is equally consistent with the grove being
     * stripped; it was never proof that junipers lack bark.
     *
     * Three distinct gob ids separates the two the only way the evidence can. A stripped patch is a
     * handful of neighbouring objects and rarely three that the bot happens to visit for the same
     * option; a species the data is simply wrong about lacks it on every gob everywhere, so the
     * third arrives quickly and costs two wasted visits to learn a fact worth the whole session.
     *
     * Per-gob memory needs none of this and is not affected: {@code exhausted} already keys on
     * {@code "g<gobId>|option"}, so an individual that lacked an option is retired individually the
     * moment it is seen, whatever this decides about its species.
     */
    private static final int LACK_CONFIRMATIONS = 3;

    /** Records that ONE gob of {@code gobResName} did not offer {@code option} in its live menu. */
    public static void menuLacks(String gobResName, String option, long gobId) {
        if ((gobResName == null) || (option == null))
            return;
        menuLacks.computeIfAbsent(gobResName + '|' + option,
            k -> Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>()))
            .add(gobId);
    }

    private static boolean lacks(String gobResName, String option) {
        Set<Long> seen = menuLacks.get(gobResName + '|' + option);
        return (seen != null) && (seen.size() >= LACK_CONFIRMATIONS);
    }

    /**
     * The same, for ONE PARTICULAR tree or bush rather than for its species.
     *
     * The species answer is the wrong one to plan a walk on. A rowan that has been picked clean
     * still belongs to a species with undiscovered berries, so it kept generating a task, the bot
     * kept walking to it, and the flower menu did not offer what it came for - which is the
     * "no known option ... menu offers: [...]" line, three of them in one 25-action run, each a
     * wasted trip to learn something that was already on screen.
     *
     * The per-gob answer already exists and is exactly what the floating LP marker draws:
     * {@link LpExplorer#allUndiscoveredProducts} gates on maturity and on the live seed/leaf
     * bitmask ({@code Sprite.decnum(d.sdt)}). So take the products it returns for THIS gob and map
     * them back to menu options, instead of asking the species what it might have.
     *
     * Bark is not bit-gated - it is assumed available on any mature tree - so it comes through
     * {@code undiscovered} the same way and needs no separate test here. What catches the species
     * that genuinely lacks it is {@link #menuLacks}, applied at the end.
     *
     * Both the planner and the executor call THIS one. They did not always: the executor kept the
     * species overload above, so the bot stopped walking to picked-clean bushes but went on asking
     * them for things once it had arrived.
     */
    public static List<String> harvestOptions(String gobResName, boolean isTree,
                                              List<String> undiscovered) {
        boolean bark = false, bough = false, leaf = false, seed = false;
        String barkName = isTree ? HarvestState.getBarkProductName(gobResName) : null;
        for (String p : undiscovered) {
            if ((barkName != null) && barkName.equals(p))
                bark = true;
            else if (LpExplorer.isBoughProduct(p))
                bough = true;
            else if (LpExplorer.isLeafProduct(p))
                leaf = true;
            else
                seed = true;
        }
        List<String> opts = new ArrayList<>();
        if (bark)
            opts.add("Take bark");
        if (bough) {
            // Both, for the same reason as above: most species say "Take bough", olive says
            // "Take branch", and the executor takes whichever the live menu actually has.
            opts.add("Take bough");
            opts.add("Take branch");
        }
        if (leaf)
            opts.add("Pick leaf");
        if (seed)
            opts.addAll(SEED_OPTIONS);
        // Drop anything this species' menu has already been seen not to offer. Left to the end so
        // the categories above stay readable, and so a species that lacks EVERY option we know
        // returns empty and generates no task at all.
        opts.removeIf(o -> lacks(gobResName, o));
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
                        List<String> opts = harvestOptions(res, HarvestSpecs.TREE.matches(res),
                            undiscovered);
                        if (!opts.isEmpty())
                            out.add(LpTask.onGob(gob, opts, null, LpTask.TIER_HARVEST, why));
                    }
                    else
                        /* Ground forage (herbs, mushrooms, kritters). "Pick" covers nearly all of
                         * them and is tried first; "Cut" is here because a few are harvested with a
                         * blade instead - standing grass is the one that showed up, logging "no
                         * known option for gfx/terobjs/herbs/standinggrass wanted=[Pick] menu
                         * offers: [Cut]" on every attempt and never yielding its LP.
                         *
                         * Listing both rather than mapping each species to its verb: the menu is
                         * asked what it actually offers and the first option it recognises wins, so
                         * an extra candidate costs nothing on the gobs that don't have it and saves
                         * maintaining a table that only ever gets discovered to be incomplete. */
                        out.add(LpTask.onGob(gob, Arrays.asList("Pick", "Cut"), null,
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
        return haven.automated.nbots.world.Hazards.nearAny(target, hazards);
    }

    /** The nearest dangerous beast inside its keep-out margin of a point, or null. */
    public static Gob hazardNear(GameUI gui, haven.Coord2d c) {
        return haven.automated.nbots.world.Hazards.near(gui, c);
    }

    /** The nearest dangerous beast within `margin` of a point, or null. */
    public static Gob hazardWithin(GameUI gui, haven.Coord2d c, double margin) {
        return haven.automated.nbots.world.Hazards.within(gui, c, margin);
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
    private static void collectInventoryTasks(GameUI gui, List<LpTask> out, Set<haven.GItem> ownGear)
            throws InterruptedException {
        if (gui.maininv == null)
            return;
        Set<String> processable = processableNames();
        List<WItem> items;
        synchronized (gui.maininv.wmap) {
            items = new ArrayList<>(gui.maininv.wmap.values());
        }
        for (WItem wi : items) {
            // The player's own, not something the bot picked up - the same line tidyInventory draws
            // before it drops anything. See the note on the ownGear parameter.
            if ((ownGear != null) && ownGear.contains(wi.item))
                continue;
            String name = wi.item.getname();
            if (name == null || !processable.contains(name))
                continue;
            out.add(LpTask.onItem(wi, CARCASS, null, name));
        }
    }
}
