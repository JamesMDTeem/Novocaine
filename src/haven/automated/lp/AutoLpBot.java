package haven.automated.lp;

import haven.Button;
import haven.Coord;
import haven.Equipory;
import haven.FlowerMenu;
import haven.GItem;
import haven.GameUI;
import haven.Gob;
import haven.ItemInfo;
import haven.Label;
import haven.Loading;
import haven.UI;
import haven.WItem;
import haven.Widget;
import haven.Window;
import haven.automated.AUtils;
import haven.resutil.FoodInfo;
import haven.automated.nbots.core.NLog;
import haven.automated.nbots.core.UiWatchdog;

import java.awt.Color;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static haven.OCache.posres;

/**
 * Walks to whatever nearby thing would yield an LP product the character hasn't discovered yet,
 * does the action that yields it, and repeats until nothing reachable is left. The execution half
 * of the LP assistant - ordering is entirely LpPlanner's (felling last, nearest first).
 *
 * Re-authored from nurgling2's AutoLpBot onto Hurricane's bot conventions: a Window+Runnable
 * launched from the menu grid (the FishingBot pattern), sleep-polling waits instead of nurgling's
 * NTask queue, gui.map.pfRightClick + AUtils.waitPf for walking, and the live FlowerMenu widget
 * inspected directly. The flower option is chosen against the LIVE menu rather than assumed from
 * the resource name - a gob that doesn't offer what we expected costs one wasted walk, not a
 * failed or wrong action.
 *
 * Two behaviours are load-bearing enough to spell out, because both were reported bugs upstream:
 *
 * - Discovery only registers for an item that lands in the player's PACK (LpExplorer's
 *   checkLpExplorer gates on the item's widget being parented to the inventory). A pick that
 *   overflows into the HAND therefore never counts. The fix is to never let the pack fill: after
 *   each action anything the bot itself collected (not the player's own starting inventory, and
 *   not carcasses it still means to process) is dropped back to the ground, since LP only needs a
 *   product's name to have been seen, not kept.
 *
 * - The game repeats a harvest until it's no longer valid - pick one apple and it picks the whole
 *   tree; chop one block and it chops the whole log. For LP a single item is enough, so every
 *   repeating action (harvest/mine/process, but NOT felling, which has no intermediate product) is
 *   cut short the moment its first item lands.
 *
 * One deliberate simplification vs nurgling (for now): tasks that need a tool (mine, log
 * processing, felling) require it ALREADY equipped in a hand - the bot checks the equipory and
 * retires the task with a chat message rather than auto-equipping. nurgling's Equip action
 * re-implemented is a chunk of its own; picking/foraging/carcass tasks need no tool at all.
 */
public class AutoLpBot extends Window implements Runnable {
    private final GameUI gui;

    private volatile boolean stop = false;
    private volatile boolean active = false;

    private final Label status;
    private final Button startButton;

    /** How far out to consider targets, in map units. Re-read from LpConfig at the start of each run. */
    private volatile double radius;
    /** Hard stop, so a mis-planned loop can't run forever unattended. */
    private final int maxActions;

    private static final String LOG = "autolp.log";

    // Targets whose every option has been tried without revealing anything new. Keyed per
    // (target, option) so a tree that has no bark left can still be asked for its bough.
    private final Set<String> exhausted = new HashSet<>();

    // The GItems already in the pack when the run started, by identity. Only items NOT in here were
    // collected by the bot and are eligible for the auto-drop - so the player's own gear is never
    // tossed. Identity-keyed because item names aren't unique and stacks merge.
    private final Set<GItem> preexisting = Collections.newSetFromMap(new IdentityHashMap<>());

    // Set when something unrecoverable happens mid-run (e.g. felling ran the character dry and
    // there was no water to drink). The run loop checks it and exits rather than plodding on.
    private volatile String fatalStop = null;

    // Set by walkTo when the approach was abandoned because of wildlife rather than because the
    // target is unreachable. The distinction decides whether the caller RETIRES the target (spent
    // for the run) or DEFERS it (skipped for a while, then reconsidered) - a bear is temporary,
    // a tree we simply cannot path to is not.
    private boolean hazardBlocked = false;

    // Targets set aside because a dangerous beast was in the way, by gob id -> the attempt number
    // at which they become eligible again. This is what keeps one wandering bear from ending a
    // whole run: before, every target near it was retired in turn until the plan came back empty
    // and the bot reported "finished" seconds after starting, having done nothing.
    private final Map<Long, Integer> deferred = new HashMap<>();
    private static final int DEFER_ATTEMPTS = 12;
    /** Total attempts that may be spent waiting for wildlife to clear before a run gives up. */
    private static final int HAZARD_WAIT_LIMIT = 60;

    /** Attempt counter, shared with the deferral bookkeeping. */
    private int attempt;

    public AutoLpBot(GameUI gui) {
        super(UI.scale(220, 60), "Auto LP");
        this.gui = gui;
        this.radius = LpConfig.radius();
        this.maxActions = 400;

        status = add(new Label("Idle."), UI.scale(10, 2));
        startButton = add(new Button(UI.scale(200), "Start") {
            @Override
            public void click() {
                if (!active) {
                    active = true;
                    change("Stop");
                } else {
                    active = false;
                    change("Start");
                }
            }
        }, UI.scale(10, 24));
        pack();
    }

    @Override
    public void run() {
        try {
            while (!stop) {
                if (active) {
                    try {
                        runOnce();
                    } catch (InterruptedException e) {
                        throw e;
                    } catch (Loading l) {
                        Thread.sleep(200);
                        continue;
                    } catch (Throwable t) {
                        NLog.crash("AutoLpBot run", t);
                        gui.error("Auto-LP hit an error (logged to logs/crash.log): " + t);
                    }
                    active = false;
                    synchronized (ui) {
                        startButton.change("Start");
                    }
                    UiWatchdog.idle();
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException ignored) {
        } finally {
            UiWatchdog.idle();
        }
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if (active)
            UiWatchdog.beat();
    }

    private void setStatus(String text) {
        synchronized (ui) {
            status.settext(text);
        }
    }

    private void runOnce() throws InterruptedException {
        if (!LpExplorer.isEnabled()) {
            gui.error("LP assistant is disabled - enable it in the LP Assistant Manager first.");
            return;
        }
        UiWatchdog.ensureStarted();

        exhausted.clear();
        preexisting.clear();
        // Per-target menu-open failures are only meaningful within a run: left standing, a target
        // that hit the retry limit in an earlier run (interrupted, walked away from) would be
        // retired on sight the next time Start is pressed, without a single attempt.
        menuFails.clear();
        deferred.clear();
        fatalStop = null;
        // Re-read rather than using the value captured when the window was opened, so changing the
        // radius in the LP Assistant Manager takes effect on the next run instead of needing the
        // bot window closed and reopened.
        radius = LpConfig.radius();
        if (gui.maininv != null) {
            synchronized (gui.maininv.wmap) {
                preexisting.addAll(gui.maininv.wmap.keySet());
            }
        }

        NLog.log(LOG, "=== run start: radius=" + radius + " maxActions=" + maxActions
            + " (pre-existing pack items: " + preexisting.size() + ") ===");

        // Route around water for the length of the run. The client can swim, so this is opt-in
        // rather than the pathfinder's default; a bot that swims off after a shoreline mushroom
        // arrives soaked, slowed and out of reach of everything it was going to do next. Restored
        // rather than simply cleared, so the setting survives whatever the player had it at.
        boolean prevBlockWater = haven.automated.pathfinder.Map.BLOCK_WATER;
        haven.automated.pathfinder.Map.BLOCK_WATER = true;
        try {
            runLoop();
        } finally {
            haven.automated.pathfinder.Map.BLOCK_WATER = prevBlockWater;
            haven.automated.pathfinder.Map.keepout(null);
        }
    }

    private void runLoop() throws InterruptedException {
        int done = 0;
        // Bounded on ATTEMPTS, not successes: any path that returns without acting (a gob that
        // vanished between planning and arriving) must not be able to spin the loop indefinitely.
        int attempts = 0;
        int maxAttempts = maxActions * 4;
        int hazardWaits = 0;

        while (active && !stop && done < maxActions && attempts < maxAttempts) {
            if (Thread.interrupted())
                throw new InterruptedException();
            attempt = ++attempts;

            List<LpTask> tasks = LpPlanner.plan(gui, radius, exhausted);
            List<LpTask> ready = new java.util.ArrayList<>(tasks.size());
            for (LpTask t : tasks) {
                if (!isDeferred(t))
                    ready.add(t);
            }
            NLog.log(LOG, "plan #" + attempts + ": " + tasks.size() + " task(s), "
                + ready.size() + " ready" + (ready.isEmpty() ? "" : ", next=" + ready.get(0)));
            setStatus("Actions: " + done + ", targets left: " + tasks.size());
            if (tasks.isEmpty())
                break;

            if (ready.isEmpty()) {
                // Everything in range is waiting out a beast. Idling here is the whole point of
                // deferring - the obstruction walks away - but it can't be unbounded, or a bear
                // that settles down for the night would keep the bot pinned until the attempt cap.
                if (++hazardWaits > HAZARD_WAIT_LIMIT) {
                    NLog.log(LOG, "=== giving up: " + tasks.size()
                        + " target(s) still blocked by wildlife after " + hazardWaits + " waits ===");
                    gui.msg("Auto-LP stopped: everything nearby is too close to a dangerous animal.",
                        Color.YELLOW);
                    setStatus("Stopped: wildlife in the way.");
                    return;
                }
                setStatus("Waiting: " + tasks.size() + " target(s) blocked by wildlife.");
                waitUntil(() -> false, 40);  // ~1s, then re-plan and see if it has moved
                continue;
            }

            LpTask task = ready.get(0);
            boolean acted = execute(task);
            NLog.log(LOG, (acted ? "acted" : "skipped") + ": " + task);
            if (acted) {
                done++;
                hazardWaits = 0;  // progress means the wildlife budget starts over
            }

            if (fatalStop != null) {
                NLog.log(LOG, "=== fatal stop: " + fatalStop + " (after " + done + " action(s)) ===");
                gui.error(fatalStop);
                setStatus("Stopped: no water/food.");
                return;
            }
        }

        NLog.log(LOG, "=== run end: " + done + " action(s), " + attempts + " attempt(s)"
            + (attempts >= maxAttempts ? " (hit attempt limit)" : "") + " ===");
        gui.msg("Auto-LP finished after " + done + " action(s).", Color.WHITE);
        setStatus("Done: " + done + " action(s).");
    }

    // ------------------------------------------------------------------ execution

    /**
     * Dispatches a task. Tree/bush harvests collect EVERY still-undiscovered category from the one
     * target in a single visit (bark, then bough, then leaf, then seed) before returning, so the
     * bot doesn't walk back to the same tree once per product.
     */
    private boolean execute(LpTask task) throws InterruptedException {
        if (!task.isItem() && task.tier == LpTask.TIER_FELL)
            return executeFell(task);

        String res = task.isItem() ? null : LpExplorer.resname(task.gob);
        boolean treeOrBush = res != null
            && (HarvestSpecs.TREE.matches(res) || HarvestSpecs.BUSH.matches(res));

        if (task.tier == LpTask.TIER_HARVEST && treeOrBush) {
            boolean any = false;
            // Bounded: a tree has at most bark+bough+leaf+seed to give, and harvestOptions shrinks
            // as each category is discovered, so this converges well inside the cap.
            for (int i = 0; i < 6; i++) {
                if (findGob(task.gob.id) == null)
                    break;
                List<String> opts = LpPlanner.harvestOptions(res, HarvestSpecs.TREE.matches(res));
                if (opts.isEmpty())
                    break;
                LpTask sub = LpTask.onGob(task.gob, opts, null, LpTask.TIER_HARVEST, task.why);
                if (!executeOnce(sub))
                    break;
                any = true;
            }
            return any;
        }
        return executeOnce(task);
    }

    private static final double FELL_DRINK_STAMINA = 0.45;
    private static final double FELL_EAT_ENERGY = 0.35;

    /**
     * Fells a standing tree for its wood LP. Unlike everything else this can't be a single click:
     * chopping is long and drains stamina, and when it runs low the character breaks off to DRINK,
     * which returns the pose to idle - a generic wait-for-idle would read that pause as "finished"
     * and walk off leaving the tree standing. So: chop, and whenever stamina bottoms out, drink
     * (AUtils.drinkTillFull) and chop again, until the tree is actually gone. Low ENERGY is a
     * fatal stop - the nurgling version tried to eat, but auto-eating is riskier than stopping.
     */
    private boolean executeFell(LpTask task) throws InterruptedException {
        tidyInventory();
        if (!toolEquipped(task.tool)) {
            NLog.log(LOG, "tool not equipped (" + task.tool.names + ") for fell " + task + " - retiring");
            gui.error("Auto-LP: equip one of " + task.tool.names + " to fell trees; skipping.");
            retire(task);
            return false;
        }
        Gob tree = findGob(task.gob.id);
        if (tree == null)
            return false;
        if (!walkTo(tree)) {
            if (hazardBlocked) {
                defer(task);
            } else {
                NLog.log(LOG, "couldn't reach " + task + " to fell it - retiring");
                retire(task);
            }
            return false;
        }
        tree = findGob(task.gob.id);
        if (tree == null)
            return false;

        NLog.log(LOG, "felling " + LpExplorer.resname(tree) + " (#" + task.gob.id + ")");
        boolean felled = fell(task.gob.id);
        NLog.log(LOG, felled ? "felled #" + task.gob.id : "fell incomplete #" + task.gob.id);
        // If it didn't fully fall (out of water, or hit the cap) retire it so the run doesn't
        // keep re-selecting the same half-chopped tree. When it does fall, its log gets planned next.
        if (!felled)
            retire(task);
        return felled;
    }

    private boolean fell(long id) throws InterruptedException {
        for (int swing = 0; swing < 300; swing++) {
            if (Thread.interrupted() || !active || stop)
                throw new InterruptedException();
            Gob tree = findGob(id);
            if (tree == null)
                return true;  // the tree fell

            // Top up before a swing if we're already low, so we don't start a chop we can't finish.
            if (energy() < FELL_EAT_ENERGY) {
                NLog.log(LOG, "fell: energy too low and auto-eating is not supported - fatal");
                fatalStop = "Auto-LP stopped: energy too low to keep chopping (eat something).";
                return findGob(id) == null;
            }
            if (stamina() <= FELL_DRINK_STAMINA) {
                AUtils.drinkTillFull(gui, 0.9, 0.9);
                waitUntil(() -> stamina() > FELL_DRINK_STAMINA, 100);
                if (stamina() <= FELL_DRINK_STAMINA) {
                    // No water to recover with - don't limp on to the next tree; stop the run.
                    NLog.log(LOG, "fell: couldn't restore stamina - fatal");
                    fatalStop = "Auto-LP stopped: couldn't drink to keep chopping (no water).";
                    return findGob(id) == null;
                }
                tree = findGob(id);
                if (tree == null)
                    return true;
                walkTo(tree);
                if (findGob(id) == null)
                    return true;
            }

            rclickAndChoose(findGob(id), "Chop");
            // Wait for the chop to actually start (or the tree to already be gone).
            waitUntil(() -> findGob(id) == null || poseContains("treechop"), 100);
            if (findGob(id) == null)
                return true;
            // Then wait for it to END - the tree falls, stamina hits the restore floor, or the
            // chop animation stops (the drink pause) - each of which loops back to re-evaluate
            // and, if the tree still stands, chop again. Tick-capped so a stuck pose can't hang.
            waitUntil(() -> findGob(id) == null
                || stamina() <= FELL_DRINK_STAMINA || energy() < FELL_EAT_ENERGY
                || !poseContains("treechop"), 2000);
        }
        return findGob(id) == null;
    }

    /** @return true if an action was actually performed (so it counts toward maxActions). */
    private boolean executeOnce(LpTask task) throws InterruptedException {
        tidyInventory();

        if (task.tool != null && !toolEquipped(task.tool)) {
            // Retiring every option rather than just the current one is deliberate: all of a
            // task's options share its tool requirement, so none can succeed until it's equipped.
            NLog.log(LOG, "tool not equipped (" + task.tool.names + ") for " + task + " - retiring");
            gui.error("Auto-LP: equip one of " + task.tool.names + " for '" + task.options.get(0)
                + "'; skipping those targets.");
            retire(task);
            return false;
        }

        FlowerMenu fm;
        if (task.isItem()) {
            WItem item = task.item;
            item.item.wdgmsg("iact", item.c, 0);
            fm = findFlowerMenu();
        } else {
            Gob gob = task.gob;
            if (findGob(gob.id) == null)
                return false;  // despawned or already felled since planning
            if (!walkTo(gob)) {
                // Either it outran us, it despawned, or wildlife got in the way. Don't right-click
                // from out of range: the menu wouldn't open, and that would burn a menu-fail retry
                // on something that isn't a menu problem.
                if (hazardBlocked)
                    defer(task);          // temporary - the beast moves, the target is still good
                else if (findGob(gob.id) != null) {
                    NLog.log(LOG, "couldn't reach " + task + " - retiring");
                    retire(task);
                }
                return false;
            }
            rclickGob(gob);
            fm = findFlowerMenu();
        }

        if (fm == null) {
            // Transient far more often than permanent - the rclick can land a frame before the
            // player finishes arriving, or the gob's menu just hasn't opened yet. Retiring the
            // whole target on the first miss is what silently lost a juniper's berries upstream
            // (then let it be felled). So retry a few times before giving up on it.
            if (bumpMenuFail(task)) {
                NLog.log(LOG, "no flower menu for " + task + " (repeated) - retiring");
                retire(task);
            } else {
                NLog.log(LOG, "no flower menu for " + task + " - will retry");
            }
            return false;
        }

        String chosen = null;
        boolean matchedButExhausted = false;
        for (String opt : task.options) {
            // For seedpick, resolve ignoring the exhausted set (so we can tell "the menu has a
            // matching Pick option but we already spent it" apart from "the menu has no matching
            // option at all") - the exhausted check is applied explicitly right after.
            String resolved = LpPlanner.SEED_PICK.equals(opt) ? resolveSeedPickAny(fm) : opt;
            if (resolved == null || !hasOpt(fm, resolved))
                continue;
            if (exhausted.contains(task.key(resolved))) {
                matchedButExhausted = true;
                continue;
            }
            chosen = resolved;
            break;
        }

        if (chosen == null) {
            if (matchedButExhausted) {
                // The menu did offer a Pick option we know - we've just already tried it without
                // a new discovery (a data gap: the picked item's name doesn't match what LpSpec
                // expects for this species, or its LP is in a seed that needs eating). Retire the
                // whole target quietly rather than spamming "no known option"; the exhausted entry
                // logged the specifics when it was first set.
                NLog.log(LOG, "all known options exhausted for " + LpExplorer.resname(task.gob)
                    + " - retiring quietly");
            } else if (hasOpt(fm, "Study")) {
                // A curiosity (Ladybug, butterfly, etc.): its LP comes from studying it at a study
                // desk, not from any carcass option - so this isn't an unknown-menu data gap, just
                // an item this forage bot doesn't handle. Retire it quietly, no chat report.
                NLog.log(LOG, "study-only curiosity " + task.why + " - retiring quietly (not a bot task)");
            } else {
                // Genuinely no option we recognize. Surface what the menu really offered (once per
                // resource) - that string is exactly what filling the data gap needs.
                reportUnknownOptions(task, fm);
            }
            closeMenu(fm);
            retire(task);
            return false;
        }

        // Snapshot what this gob still owes us, so we can tell afterwards whether the action
        // actually revealed anything. Items are one-shot (a butchered carcass is consumed), so
        // they're retired unconditionally instead.
        List<String> before = task.isItem() ? null : undiscoveredOf(task.gob);

        NLog.log(LOG, "pick '" + chosen + "' on " + (task.isItem() ? ("item " + task.why)
            : LpExplorer.resname(task.gob)) + " (undiscovered before: " + before + ")");

        if (!chooseOpt(fm, chosen)) {
            NLog.log(LOG, "chooseOpt('" + chosen + "') failed - exhausting");
            exhausted.add(task.key(chosen));
            return false;
        }

        // Felling has no intermediate product - the log only appears when the tree fully falls -
        // so it must run to completion. Everything else repeats (pick every apple, chop every
        // block) and is cut short the instant its first item lands, which is all LP needs.
        boolean stopAfterFirst = !task.isItem() && task.tier != LpTask.TIER_FELL;

        // Let the action actually run before doing anything else. Without this the bot re-planned
        // and re-clicked the instant the menu closed, long before the item was collected - so the
        // before/after discovery comparison below always saw "no change" and wrongly retired the
        // option, and the character was pelted with clicks faster than it could act on them.
        waitForActionComplete(stopAfterFirst);

        if (task.isItem()) {
            retire(task);
            return true;
        }

        // If the gob is gone the action consumed it, which counts as progress whatever it
        // yielded. Otherwise compare before/after - but only AFTER giving the pickup a chance to
        // register (waitForDiscovery), since the item resolves its name and posts its discovery
        // asynchronously once it's in the pack. Comparing too early reads a real discovery as
        // "nothing new" and wrongly retires the target - the false negative that ended runs early
        // upstream. An unchanged set even after settling means this option genuinely has nothing
        // left to give (or, like a Medlar fruit whose LP lives in its SEED, needs the eat step,
        // not more picking - handled by the eat-on-tidy toggle), so don't ask it again.
        Gob still = findGob(task.gob.id);
        if (still == null) {
            NLog.log(LOG, "gob consumed by '" + chosen + "'");
        } else {
            List<String> beforeSnap = before;
            waitUntil(() -> !undiscoveredOf(still).equals(beforeSnap), 80);
            List<String> after = undiscoveredOf(still);
            if (after.equals(before)) {
                NLog.log(LOG, "'" + chosen + "' yielded no new discovery (still " + after
                    + ") - exhausting this option");
                exhausted.add(task.key(chosen));
                // If this was a seed pick, also spend the sentinel so the multi-collect loop and
                // the planner stop re-offering the same species (whose picked item name our data
                // doesn't recognize) - otherwise it re-resolves to the same Pick option forever.
                if (task.options.contains(LpPlanner.SEED_PICK))
                    exhausted.add(task.key(LpPlanner.SEED_PICK));
            } else {
                NLog.log(LOG, "'" + chosen + "' discovered something: " + before + " -> " + after);
            }
        }
        return true;
    }

    /**
     * Blocks until the flower-menu action has actually run to completion (or, when stopAfterFirst,
     * until its first item lands and the repeat is halted). Different actions take very different
     * times - a bark pick is quick, sawing a log into boards is slow - so rather than a fixed
     * delay this keys off the player's animation pose: wait to LEAVE idle (the action started),
     * then to RETURN to idle (it finished). Both phases are bounded, so an action that never
     * changes pose can't hang the bot.
     */
    private void waitForActionComplete(boolean stopAfterFirst) throws InterruptedException {
        final int initialFree = freeSpace();

        // Phase 1: wait for the action to start (pose leaves idle) OR the first item to already
        // have landed. Bounded LOW (~0.5s): a quick pick may barely change pose, and the old
        // 3-second bound spent that whole time dead-waiting for a pose flash that never came -
        // the single biggest source of the between-action lag. Detecting the item landing here
        // means an instant pick falls straight through instead of waiting out the bound.
        waitUntil(() -> {
            int free = freeSpace();
            boolean gotItem = (free >= 0 && free < initialFree) || gui.vhand != null;
            return !poseContains("idle") || gotItem;
        }, 20);

        // Phase 2: wait for it to finish (back to idle), the first item to land (if we mean to
        // stop after one), or the pack to fill. Bounded high - sawing legitimately takes a while.
        waitUntil(() -> {
            boolean idle = poseContains("idle");
            int free = freeSpace();
            boolean gotItem = stopAfterFirst && ((free >= 0 && free < initialFree) || gui.vhand != null);
            boolean full = free == 0;
            return idle || gotItem || full;
        }, 600);

        // Halt the repeat so the game doesn't pick the whole tree / chop the whole log. A move to
        // our own tile is the standard interrupt. Harmless if the action already ended.
        if (stopAfterFirst && !poseContains("idle")) {
            stopAction();
            waitUntil(() -> poseContains("idle"), 40);
        }
    }

    // ------------------------------------------------------------------ inventory tidy-up

    /**
     * Keeps the pack from ever filling, which is what caused every downstream symptom: a full pack
     * sends the next pick to the HAND, where its name never resolves inside the inventory widget
     * and so never registers as a discovery. First clears the hand (a full hand blocks all
     * pickups), then drops everything the bot itself collected that it doesn't need to keep.
     *
     * Only bot-collected items are ever dropped - the player's starting inventory (snapshotted at
     * run start) is left untouched, so this can't throw away their gear. Tools stay (dropping the
     * axe mid-run would strand every felling task) and processable carcasses stay (they're pending
     * LP tasks in their own right). Everything else is a harvested product whose name has already
     * been seen, so dropping it costs nothing toward the goal.
     */
    private void tidyInventory() throws InterruptedException {
        if (gui.maininv == null)
            return;

        boolean eatConsumables = LpConfig.on(LpConfig.Key.autolpEatConsumables);

        if (gui.vhand != null)
            dropHeldToGround();

        List<WItem> items;
        synchronized (gui.maininv.wmap) {
            items = new java.util.ArrayList<>(gui.maininv.wmap.values());
        }
        for (WItem wi : items) {
            if (preexisting.contains(wi.item))
                continue;  // the player's own gear, not something the bot picked up
            String name = wi.item.getname();
            if (name == null || isKeepable(name) || LpPlanner.isProcessable(name))
                continue;
            // Some picked fruits carry their LP in a SEED you only get by EATING them (a Medlar
            // gives Medlar Seed, which is the tracked product - picking the fruit alone discovers
            // nothing). With the toggle on, eat those instead of dropping so the seed lands and
            // registers. Off by default because eating affects the character's food meter/FEP.
            if (eatConsumables && isConsumable(wi)) {
                eatForSeed(wi, name);
                continue;
            }
            dropToGround(wi);
        }
    }

    /** True if this item is food - eating it might yield a seed that's the actual LP product. */
    private boolean isConsumable(WItem wi) {
        try {
            List<ItemInfo> infos = wi.item.info();
            if (infos != null) {
                for (ItemInfo ii : infos) {
                    if (ii instanceof FoodInfo)
                        return true;
                }
            }
        } catch (Loading l) {
            // Info not ready this pass - treat as not-food, it'll be reconsidered next tidy.
        }
        return false;
    }

    /**
     * Eats a pickable consumable instead of dropping it, so a fruit whose LP lives in its seed
     * yields that seed into the pack (where it registers as a discovery, then gets dropped next
     * tidy). Falls back to dropping if the item won't eat, so a food with no Eat option never
     * wedges the tidy-up.
     */
    private void eatForSeed(WItem wi, String name) throws InterruptedException {
        NLog.log(LOG, "eating consumable '" + name + "' for its seed LP");
        // Snapshot the pack before eating so the seed the eat leaves behind can be told apart from
        // everything already there, and dropped right away rather than lingering until the next
        // pick's tidy pass (which is what made eaten seeds appear to "queue up").
        Set<GItem> beforeEat = snapshotPack();
        wi.item.wdgmsg("iact", wi.c, 0);
        FlowerMenu fm = findFlowerMenu();
        boolean ate = false;
        if (fm != null && hasOpt(fm, "Eat"))
            ate = chooseOpt(fm, "Eat");
        else if (fm != null)
            closeMenu(fm);
        if (!ate) {
            NLog.log(LOG, "eat of '" + name + "' didn't take - dropping instead");
            dropToGround(wi);
            return;
        }
        waitUntil(() -> poseContains("idle"), 100);
        // Wait for the seed to actually land in the pack (a new item appears), then a short beat
        // for its name to resolve and register as a discovery (GItem.tick's possession gate),
        // before dropping it - dropping too early would throw the seed away before it counts.
        waitUntil(() -> !newDroppable(beforeEat).isEmpty(), 40);
        waitUntil(() -> false, 12);  // ~0.3s settle so the discovery registers first
        for (WItem seed : newDroppable(beforeEat)) {
            NLog.log(LOG, "dropping eaten-seed product '" + seed.item.getname() + "'");
            dropToGround(seed);
        }
    }

    /** Identity snapshot of everything currently in the pack. */
    private Set<GItem> snapshotPack() {
        Set<GItem> s = Collections.newSetFromMap(new IdentityHashMap<>());
        if (gui.maininv != null) {
            synchronized (gui.maininv.wmap) {
                s.addAll(gui.maininv.wmap.keySet());
            }
        }
        return s;
    }

    /** Pack items that appeared since `before` and are safe to drop (not gear/tools/carcasses). */
    private List<WItem> newDroppable(Set<GItem> before) {
        List<WItem> out = new java.util.ArrayList<>();
        if (gui.maininv == null)
            return out;
        List<WItem> items;
        synchronized (gui.maininv.wmap) {
            items = new java.util.ArrayList<>(gui.maininv.wmap.values());
        }
        for (WItem wi : items) {
            if (before.contains(wi.item) || preexisting.contains(wi.item))
                continue;
            String nm = wi.item.getname();
            if (nm == null || isKeepable(nm) || LpPlanner.isProcessable(nm))
                continue;
            out.add(wi);
        }
        return out;
    }

    private void dropHeldToGround() throws InterruptedException {
        Gob p = player();
        if (p == null)
            return;
        gui.map.wdgmsg("drop", Coord.z, p.rc.floor(posres), 0);
        waitUntil(() -> gui.vhand == null, 50);
    }

    private void dropToGround(WItem wi) throws InterruptedException {
        wi.item.wdgmsg("take", Coord.z);
        waitUntil(() -> gui.vhand != null, 50);
        Gob p = player();
        if (p != null)
            gui.map.wdgmsg("drop", Coord.z, p.rc.floor(posres), 0);
        waitUntil(() -> gui.vhand == null, 50);
    }

    // Tools have to survive the cull - dropping the axe mid-run would strand every felling task.
    // Matched case-insensitively so a transcription slip in either direction ("cleaver" here vs
    // the game's "Butcher's Cleaver") can't let a tool through into the auto-drop.
    private boolean isKeepable(String name) {
        String n = name.toLowerCase();
        return n.contains(" axe") || n.contains(" saw") || n.contains("cleaver")
            || n.contains("pickaxe") || n.contains("shovel");
    }

    // ------------------------------------------------------------------ menu helpers

    /**
     * Resolves the species-specific seed/fruit "Pick x" option against the live menu. The suffix
     * varies per species (cone, berries, fruits, drupes, hips, catkin, ...) so it can't be a
     * fixed string; instead we take whatever "Pick x" the menu offers, minus the options that are
     * really a DIFFERENT category ("Pick leaf") or unrelated ("Pick up" on a dropped item). Never
     * matches "Chop", "Take bark" or "Take branch", so this can't turn into a felling by accident.
     */
    private String resolveSeedPickAny(FlowerMenu fm) {
        for (FlowerMenu.Petal petal : fm.opts) {
            String n = petal.name;
            if (n != null && n.startsWith("Pick ") && !n.equals("Pick leaf") && !n.equals("Pick up"))
                return n;
        }
        return null;
    }

    private boolean hasOpt(FlowerMenu fm, String name) {
        for (FlowerMenu.Petal petal : fm.opts) {
            if (name.equals(petal.name))
                return true;
        }
        return false;
    }

    private boolean chooseOpt(FlowerMenu fm, String name) throws InterruptedException {
        for (FlowerMenu.Petal petal : fm.opts) {
            if (name.equals(petal.name)) {
                fm.wdgmsg("cl", petal.num, 0);
                waitUntil(() -> findFlowerMenuNow() == null, 50);
                return true;
            }
        }
        return false;
    }

    private void closeMenu(FlowerMenu fm) throws InterruptedException {
        fm.wdgmsg("cl", -1);
        waitUntil(() -> findFlowerMenuNow() == null, 50);
    }

    /** Polls briefly for a flower menu to open (the rclick lands asynchronously). */
    private FlowerMenu findFlowerMenu() throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            if (!active || stop)
                throw new InterruptedException();
            FlowerMenu fm = findFlowerMenuNow();
            if (fm != null)
                return fm;
            Thread.sleep(POLL_MS);
        }
        return null;
    }

    private FlowerMenu findFlowerMenuNow() {
        return findChild(gui.ui.root, FlowerMenu.class);
    }

    private static <T extends Widget> T findChild(Widget root, Class<T> cls) {
        for (Widget w = root.child; w != null; w = w.next) {
            if (cls.isInstance(w))
                return cls.cast(w);
            T deep = findChild(w, cls);
            if (deep != null)
                return deep;
        }
        return null;
    }

    // Per-target count of consecutive "menu wouldn't open" misses. Returns true once it crosses
    // the retry threshold, meaning the caller should stop retrying this target.
    private final Map<String, Integer> menuFails = new HashMap<>();
    private static final int MENU_FAIL_LIMIT = 3;

    private boolean bumpMenuFail(LpTask task) {
        String k = task.isItem() ? "i" + System.identityHashCode(task.item) : "g" + task.gob.id;
        return menuFails.merge(k, 1, Integer::sum) >= MENU_FAIL_LIMIT;
    }

    // Resources already reported, so a forest of the same species doesn't spam the chat log.
    private final Set<String> reported = new HashSet<>();

    /**
     * Tells the player which flower options a target actually offered when none of the ones we
     * know about matched. This is the feedback path for filling gaps in LpPlanner's option lists -
     * the alternative would be guessing at strings, which risks picking a destructive option.
     */
    private void reportUnknownOptions(LpTask task, FlowerMenu fm) {
        String label = task.isItem() ? ("item " + task.why) : LpExplorer.resname(task.gob);
        if (!reported.add(label))
            return;
        StringBuilder available = new StringBuilder();
        for (FlowerMenu.Petal petal : fm.opts) {
            if (available.length() > 0)
                available.append(", ");
            available.append(petal.name);
        }
        // Log it too, so species we can't yet handle (a dogrose whose fruit option we don't know)
        // leave a record of exactly what their menu offered - that string is what a fix needs.
        NLog.log(LOG, "no known option for " + label + " wanted=" + task.options
            + " menu offers: [" + available + "]");
        gui.msg("Auto-LP: no known option for " + label + " (wanted " + task.options
            + ") - menu offers: " + available, Color.YELLOW);
    }

    // ------------------------------------------------------------------ world helpers

    private Gob player() {
        return (gui.map != null) ? gui.map.player() : null;
    }

    private Gob findGob(long id) {
        return gui.ui.sess.glob.oc.getgob(id);
    }

    /** Close enough to right-click a gob and have the menu open. Roughly two tiles. */
    private static final double REACH = 22.0;
    /** How far a target may drift from where we aimed before the path is worth recomputing. */
    private static final double DRIFT = 11.0;
    /** Give up chasing after this many re-paths without getting closer. */
    private static final int NO_PROGRESS_LIMIT = 10;

    private boolean walking() {
        Gob me = player();
        return me != null && ((gui.map.pfthread != null && gui.map.pfthread.isAlive()) || me.getv() > 0);
    }

    /**
     * Stops walking for good: kills the pathfinder as well as the current move.
     *
     * stopAction() alone isn't enough here - it clicks our own tile, which ends the CURRENT move,
     * but the Pathfinder thread is still alive and simply issues the next leg of its route, so we'd
     * keep walking towards whatever we were trying to walk away from. pfRightClick does this
     * teardown itself before starting a new search, which is why re-pathing needs no equivalent;
     * only the abandon case does. Same sequence GameUI uses to cancel a path.
     */
    private void cancelWalk() {
        synchronized (haven.automated.pathfinder.Pathfinder.class) {
            if (gui.map.pf != null) {
                gui.map.pf.terminate = true;
                if (gui.map.pfthread != null)
                    gui.map.pfthread.interrupt();
            }
        }
        stopAction();
    }

    /**
     * Walks to a gob, re-pathing as it moves.
     *
     * The old version fired one pfRightClick and blocked in AUtils.waitPf until the whole path had
     * been walked. For a tree that's fine - it's still there when you arrive. For anything that
     * MOVES it's useless, and produced exactly the reported silkmoth behaviour: click, moth drifts
     * away, character dutifully finishes walking to where the moth used to be, only then looks
     * again. With a target that moves continuously that never converges.
     *
     * So instead of waiting out the path, this watches the target and re-issues the path whenever it
     * has drifted more than DRIFT from the point we aimed at - i.e. it intercepts rather than
     * follows. pfRightClick already terminates the in-flight pathfinder and cancels the current
     * movement before starting a new search, so re-issuing mid-walk is exactly what it's built for.
     * Static targets cost nothing extra: their drift is always zero, so the re-path never fires and
     * this behaves like the single click it replaces.
     *
     * Wildlife is routed AROUND rather than run from. Every re-path publishes the beasts' aggro
     * rings to the pathfinder as keep-out circles (LpPlanner.keepouts), so A* plans a detour; if one
     * has already closed to within its ring, the character backs out of it first, because a search
     * whose own starting cell sits inside a blocked region has no legal move at all. This replaces
     * an earlier "abandon the approach the moment a beast is near" rule, which read as the bot
     * quitting: the abandoned target got retired, the next one was equally near the same bear, and
     * a run could retire every target it had and finish in seconds without acting once.
     *
     * Three bail-outs, all of which leave the target DEFERRED rather than spent (see hazardBlocked)
     * when wildlife was the cause: the target itself ends up inside a keep-out, we can't get clear
     * of a beast after RETREAT_LIMIT tries, or - for a fleeing critter that can outrun a walking
     * character indefinitely - the distance stops improving over NO_PROGRESS_LIMIT re-paths.
     *
     * @return true if we ended up within reach of the target.
     */
    private boolean walkTo(Gob gob) throws InterruptedException {
        hazardBlocked = false;
        try {
            return walkToward(gob);
        } finally {
            // Never leave a keep-out standing. It's a process-wide setting and the player's own
            // clicks go through the same pathfinder, so a leftover ring would silently reroute
            // them long after the bot stopped caring.
            haven.automated.pathfinder.Map.keepout(null);
        }
    }

    private boolean walkToward(Gob gob) throws InterruptedException {
        long id = gob.id;
        haven.Coord2d aimed = null;
        double best = Double.MAX_VALUE;
        int stalled = 0;
        int retreats = 0;

        for (int i = 0; i < 60; i++) {
            Gob target = findGob(id);
            Gob me = player();
            if (target == null || me == null)
                return false;

            double dist = me.rc.dist(target.rc);
            if (dist <= REACH)
                return true;

            // A beast that has wandered onto the target since it was planned. Standing there to
            // work is what the full keep-out margin exists to prevent, so stop - but defer, since
            // beasts move and the target will be worth having again shortly.
            Gob atTarget = LpPlanner.hazardWithin(gui, target.rc, LpTargets.DANGER_KEEPOUT);
            if (atTarget != null) {
                NLog.log(LOG, "deferring #" + id + ": " + LpExplorer.resname(atTarget)
                    + " is within keep-out of it");
                hazardBlocked = true;
                cancelWalk();
                return false;
            }

            // One that has closed on US. Can't be handed to the pathfinder as a no-go circle while
            // we're inside it, so step out of it and re-path on the next pass.
            Gob onUs = LpPlanner.hazardWithin(gui, me.rc, LpTargets.DANGER_PATH_CLEARANCE);
            if (onUs != null) {
                if (++retreats > RETREAT_LIMIT) {
                    NLog.log(LOG, "deferring #" + id + ": still inside "
                        + LpExplorer.resname(onUs) + "'s ring after " + retreats + " retreats");
                    hazardBlocked = true;
                    cancelWalk();
                    return false;
                }
                NLog.log(LOG, "backing away from " + LpExplorer.resname(onUs) + " ("
                    + (int) me.rc.dist(onUs.rc) + "u) before continuing to #" + id);
                retreatFrom(onUs);
                aimed = null;  // we've moved; whatever we aimed at is stale
                continue;
            }

            if (dist < best - 1.0) {
                best = dist;
                stalled = 0;
            }

            if (aimed == null || aimed.dist(target.rc) > DRIFT) {
                if (aimed != null && ++stalled > NO_PROGRESS_LIMIT) {
                    NLog.log(LOG, "giving up chase of #" + id + " ("
                        + LpExplorer.resname(target) + "): " + stalled
                        + " re-paths without closing (still " + (int) dist + "u)");
                    cancelWalk();
                    return false;
                }
                // Refreshed per re-path rather than once per walk: the beasts move too, and a stale
                // ring would route us around where a bear used to be and through where it now is.
                haven.automated.pathfinder.Map.keepout(LpPlanner.keepouts(gui, me.rc));
                // clickb=1 walks without acting on arrival; the right-click is ours to time.
                gui.map.pfRightClick(target, -1, 1, 0, null);
                aimed = target.rc;
            }

            // Let a freshly-issued path actually get going first. Without this grace the check
            // below reads the gap between the click and the pathfinder thread starting as "we've
            // stopped walking, so we must have arrived", and the loop spins at poll speed instead
            // of walking anywhere. (AUtils.waitPf opens with the same fixed sleep, for the same
            // reason.) It doubles as the loop's tick rate when no re-path was needed.
            waitUntil(() -> false, 10);

            // Then wait a slice rather than the whole path, so a target that moves is noticed while
            // we're still walking - and end the slice early once we're in reach or the walk is over.
            waitUntil(() -> {
                Gob g = findGob(id);
                Gob p = player();
                if (g == null || p == null)
                    return true;
                if (p.rc.dist(g.rc) <= REACH)
                    return true;
                return !walking();
            }, 12);

            // The walk finished and the target is still where we aimed: this is as close as pathing
            // is going to get us, so treat it as arrival even if we're further away than REACH.
            // Distance alone can't decide this - pfRightClick paths to the edge of the gob's HITBOX,
            // and a big tree's trunk is wider than two tiles, so demanding REACH would have left
            // the bot circling every large tree until it gave up.
            Gob now = findGob(id);
            if (!walking() && now != null && aimed != null && aimed.dist(now.rc) <= DRIFT)
                return true;
        }
        NLog.log(LOG, "walk to #" + id + " ran out of attempts");
        Gob me = player(), target = findGob(id);
        if (me != null && target != null && me.rc.dist(target.rc) <= REACH)
            return true;
        cancelWalk();
        return false;
    }

    /** How many times one approach may stop to back out of a beast's ring before giving up. */
    private static final int RETREAT_LIMIT = 3;
    /** Extra distance past the ring's edge to aim for, so a step or two of drift doesn't re-trip it. */
    private static final double RETREAT_MARGIN = 30.0;

    /**
     * Walks directly away from a beast until we're outside the ring the pathfinder needs to treat
     * as a no-go area.
     *
     * Uses pfLeftClick rather than a raw move so the retreat still goes around trees and water, but
     * it is issued while no keep-out is published - by definition we are standing in the one that
     * matters, and blocking it would leave the search no route out of where we already are.
     */
    private void retreatFrom(Gob beast) throws InterruptedException {
        Gob me = player();
        if (me == null || beast == null)
            return;
        haven.Coord2d away = me.rc.sub(beast.rc);
        double d = away.abs();
        // Dead-centre on the beast has no "away" direction; any heading beats standing still.
        away = (d < 1.0) ? new haven.Coord2d(1, 0) : away.div(d);
        haven.Coord2d dest = beast.rc.add(
            away.mul(LpTargets.DANGER_PATH_CLEARANCE + RETREAT_MARGIN));

        haven.automated.pathfinder.Map.keepout(null);
        gui.map.pfLeftClick(dest.floor(), null);
        waitUntil(() -> {
            Gob p = player();
            Gob b = findGob(beast.id);
            if (p == null || b == null)
                return true;
            return p.rc.dist(b.rc) > LpTargets.DANGER_PATH_CLEARANCE + RETREAT_MARGIN;
        }, 200);
        cancelWalk();
    }

    private void rclickGob(Gob gob) {
        gui.map.wdgmsg("click", Coord.z, gob.rc.floor(posres), 3, 0, 0, (int) gob.id,
            gob.rc.floor(posres), 0, -1);
    }

    private void rclickAndChoose(Gob gob, String option) throws InterruptedException {
        if (gob == null)
            return;
        rclickGob(gob);
        FlowerMenu fm = findFlowerMenu();
        if (fm != null) {
            if (!chooseOpt(fm, option))
                closeMenu(fm);
        }
    }

    /** Move-to-self: the standard way to interrupt a repeating in-place action. */
    private void stopAction() {
        Gob p = player();
        if (p != null)
            gui.map.wdgmsg("click", Coord.z, p.rc.floor(posres), 1, 0);
    }

    private boolean poseContains(String s) {
        Gob p = player();
        if (p == null)
            return false;
        try {
            for (String pose : p.getPoses()) {
                if (pose != null && pose.contains(s))
                    return true;
            }
        } catch (Loading l) {
            return false;
        }
        return false;
    }

    // IMeter.Meter.a is already a 0..1 fraction (cf. CellarDiggingBot's getmeter("stam",0).a < 0.40),
    // NOT a 0..100 percentage - so it's used directly, not divided.
    private double stamina() {
        haven.IMeter.Meter m = gui.getmeter("stam", 0);
        return m == null ? 1.0 : m.a;
    }

    private double energy() {
        haven.IMeter.Meter m = gui.getmeter("nrj", 0);
        return m == null ? 1.0 : m.a;
    }

    /** Free cells in the pack, or -1 if the inventory isn't ready. */
    private int freeSpace() {
        try {
            return (gui.maininv == null) ? -1 : gui.maininv.getFreeSpace();
        } catch (Exception e) {
            return -1;
        }
    }

    /** True if one of the alias' tools is already equipped in a hand (or held). */
    private boolean toolEquipped(LpAlias tool) {
        if (tool == null)
            return true;
        Equipory e = gui.getequipory();
        if (e == null)
            return false;
        for (WItem wi : e.slots) {
            if (wi == null)
                continue;
            String name = wi.item.getname();
            if (tool.matches(name))
                return true;
        }
        return false;
    }

    private List<String> undiscoveredOf(Gob gob) {
        try {
            return LpExplorer.allUndiscoveredProducts(gob);
        } catch (Loading l) {
            return Collections.emptyList();
        }
    }

    private void retire(LpTask task) {
        for (String opt : task.options)
            exhausted.add(task.key(opt));
    }

    /**
     * Sets a target aside for a while instead of spending it.
     *
     * Keyed by gob rather than by (gob, option) as retire() is: what's blocking it is a beast
     * standing nearby, which has nothing to do with which flower option we wanted, so every option
     * on that target waits together.
     */
    private void defer(LpTask task) {
        if (task.isItem())
            return;  // inventory tasks cost no travel and can't be blocked by anything out there
        deferred.put(task.gob.id, attempt + DEFER_ATTEMPTS);
        NLog.log(LOG, "deferred " + task + " until attempt " + (attempt + DEFER_ATTEMPTS));
    }

    private boolean isDeferred(LpTask task) {
        if (task.isItem())
            return false;
        Integer until = deferred.get(task.gob.id);
        return until != null && attempt < until;
    }

    private interface Cond {
        boolean check() throws InterruptedException;
    }

    private static final int POLL_MS = 25;

    /**
     * Sleep-polling wait: checks the condition first (so an already-true condition returns with no
     * delay), then sleeps POLL_MS between checks, up to maxTicks. The 25ms granularity keeps the
     * bot responsive between actions - at 50ms the gaps were visibly laggier than nurgling's
     * event-driven waits.
     */
    private void waitUntil(Cond cond, int maxTicks) throws InterruptedException {
        for (int i = 0; i < maxTicks; i++) {
            if (!active || stop)
                throw new InterruptedException();
            try {
                if (cond.check())
                    return;
            } catch (Loading l) {
                // keep waiting
            }
            Thread.sleep(POLL_MS);
        }
    }

    public void stop() {
        active = false;
        stop = true;
        UiWatchdog.idle();
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if (msg.equals("close")) {
            stop();
            hide();
            if (gui != null && gui.autoLpBot == this) {
                gui.autoLpBot = null;
                gui.autoLpThread = null;
            }
            reqdestroy();
            return;
        }
        super.wdgmsg(sender, msg, args);
    }
}
