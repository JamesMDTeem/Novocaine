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
        int done = 0;
        // Bounded on ATTEMPTS, not successes: any path that returns without acting (a gob that
        // vanished between planning and arriving) must not be able to spin the loop indefinitely.
        int attempts = 0;
        int maxAttempts = maxActions * 4;

        while (active && !stop && done < maxActions && attempts < maxAttempts) {
            if (Thread.interrupted())
                throw new InterruptedException();
            attempts++;

            List<LpTask> tasks = LpPlanner.plan(gui, radius, exhausted);
            NLog.log(LOG, "plan #" + attempts + ": " + tasks.size() + " task(s)"
                + (tasks.isEmpty() ? "" : ", next=" + tasks.get(0)));
            setStatus("Actions: " + done + ", targets left: " + tasks.size());
            if (tasks.isEmpty())
                break;

            LpTask task = tasks.get(0);
            boolean acted = execute(task);
            NLog.log(LOG, (acted ? "acted" : "skipped") + ": " + task);
            if (acted)
                done++;

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
        walkTo(tree);
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
            walkTo(gob);
            if (findGob(gob.id) == null)
                return false;
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

    private void walkTo(Gob gob) throws InterruptedException {
        // clickb=3 both paths to the gob and right-clicks it on arrival, but we click again
        // ourselves after arriving (rclickGob) so the flower menu open is under OUR timing.
        gui.map.pfRightClick(gob, -1, 1, 0, null);
        AUtils.waitPf(gui);
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
