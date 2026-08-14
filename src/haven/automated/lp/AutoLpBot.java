package haven.automated.lp;

import haven.Button;
import haven.Coord;
import haven.Coord2d;
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
import haven.automated.helpers.CollisionGeom;
import haven.automated.helpers.HitBoxes;
import haven.automated.pathfinder.World;
import haven.resutil.FoodInfo;
import haven.automated.nbots.core.Carried;
import haven.automated.nbots.core.NLog;
import haven.automated.nbots.core.UiWatchdog;
import haven.automated.nbots.core.Widgets;
import haven.automated.nbots.world.BotNav;
import haven.automated.nbots.world.Hazards;

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
    private final BotNav nav;

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
    /** Ticks to wait for a discovery to register after an action that yields one (eat for seed, etc.). */
    private static final int DISCOVERY_SETTLE_TICKS = 12;

    /** Attempt counter, shared with the deferral bookkeeping. */
    private int attempt;

    public AutoLpBot(GameUI gui) {
        super(UI.scale(220, 60), "Auto LP");
        this.gui = gui;
        this.radius = LpConfig.radius();
        this.maxActions = 400;
        this.nav = new BotNav(gui, () -> active && !stop, LOG, Hazards::keepouts);

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
        haven.automated.pathfinder.Map.avoidWater(this, true);
        try {
            runLoop();
        } finally {
            haven.automated.pathfinder.Map.avoidWater(this, false);
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

            LpPlanner.PlanResult plan = LpPlanner.plan(gui, radius, exhausted, preexisting);
            List<LpTask> tasks = plan.tasks;
            List<LpTask> ready = new java.util.ArrayList<>(tasks.size());
            for (LpTask t : tasks) {
                if (!isDeferred(t))
                    ready.add(t);
            }
            NLog.log(LOG, "plan #" + attempts + ": " + tasks.size() + " task(s), "
                + ready.size() + " ready"
                + ((plan.walledOff > 0) ? (", " + plan.walledOff + " walled off") : "")
                + (ready.isEmpty() ? "" : ", next=" + ready.get(0)));
            setStatus("Actions: " + done + ", targets left: " + tasks.size());
            if (tasks.isEmpty()) {
                /* Say WHY there is nothing left. "Finished" and "everything that is left is behind
                 * a wall I cannot open" are completely different outcomes to the person reading the
                 * status, and they used to print the same. */
                if (plan.walledOff > 0) {
                    NLog.log(LOG, "=== nothing reachable left: " + plan.walledOff
                        + " remaining target(s) are inside walls we are not inside ===");
                    gui.msg("Auto-LP finished: the only targets left are inside walls"
                        + " this bot cannot enter.", Color.YELLOW);
                    setStatus("Done: " + plan.walledOff + " target(s) left, all walled off.");
                }
                break;
            }

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
            /* Checked here rather than while building the list, because it costs a search and the
             * list is rebuilt every attempt: only the one we are about to walk to is worth proving.
             * Retired, not deferred - neither a river nor a palisade is going to move. */
            String unreachable = whyUnwalkable(task);
            if (unreachable != null) {
                NLog.log(LOG, "skipping " + task + ": " + unreachable);
                retire(task);
                continue;
            }
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

        /* Once more on the way out. Tidying runs at the START of an action, which handles everything
         * the run picks up except the last thing it picks up - there is no next action to clear it.
         * So the final pick sat in the pack: not dropped, and, when it is a fruit whose LP is in the
         * seed, not eaten either, which quietly loses the discovery the whole trip was for. */
        tidyInventory();
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
                Gob live = findGob(task.gob.id);
                if (live == null)
                    break;
                /* Ask THIS tree, not its species - the same question the planner asks.
                 *
                 * The two-argument overload answers for the species: it reads LpSpec and the
                 * session's discoveries and knows nothing about the gob standing in front of us.
                 * So a rowan already picked clean still belongs to a species with undiscovered
                 * berries, and this loop kept offering options the individual does not carry -
                 * seven "no known option ... menu offers: [...]" lines in one run, each a menu
                 * opened to learn what was already on screen. It also skipped the menuLacks
                 * filter, so a juniper's absent bark was re-tried inside the same visit even
                 * after the session had watched the menu and learned better.
                 *
                 * The three-argument overload takes the products for THIS gob - maturity and the
                 * live seed/leaf bitmask already applied - and ends by dropping anything the
                 * species' menu has been seen not to offer. The planner has called it that way
                 * since per-gob availability landed; only the executor was left behind, which is
                 * why the bot stopped WALKING to picked-clean bushes but went on asking them for
                 * things once it arrived. */
                List<String> opts = LpPlanner.harvestOptions(res, HarvestSpecs.TREE.matches(res),
                    LpExplorer.allUndiscoveredProducts(live));
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
     * Below this fraction of what the carried vessels hold, the water really has run out.
     *
     * A dreg is not a drink. Five percent is low enough that anything above it is worth another
     * mouthful and a moment for the meter to catch up, and low enough that a genuinely empty flask
     * still ends the run rather than leaving the bot chopping at a tree it cannot fell.
     */
    private static final double FELL_LOW_WATER = 0.05;

    /**
     * How many times a swing may find low stamina, water in hand, and no recovery yet.
     *
     * Bounded because the alternative is a bot standing at a tree drinking forever. Five is well
     * past a slow drink and well short of a stall; the swing budget bounds it again from outside.
     */
    private static final int FELL_DRINK_RETRIES = 5;

    /**
     * Fells a standing tree for its wood LP. Unlike everything else this can't be a single click:
     * chopping is long and drains stamina, and when it runs low the character breaks off to DRINK,
     * which returns the pose to idle - a generic wait-for-idle would read that pause as "finished"
     * and walk off leaving the tree standing. So: chop, and whenever stamina bottoms out, drink
     * ({@code Carried.drink} - see {@link #fell}, it was drinkTillFull and that could not see a
     * worn flask) and chop again, until the tree is actually gone. Low ENERGY is a fatal stop -
     * the nurgling version tried to eat, but auto-eating is riskier than stopping.
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
            if (hazardBlocked)
                defer(task);
            else
                cannotWalkTo(task);
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
        int thirstyRetries = 0;
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
                /* Carried.drink, NOT AUtils.drinkTillFull, and never both.
                 *
                 * drinkTillFull goes through GameUI.drink, which scans inventories for a flask but
                 * checks only equipment slots 6 and 7 and only for a bucket-water - so a Waterflask
                 * WORN is invisible to it. It returns false, drinkTillFull does nothing, and the
                 * stamina test below then reads that as "no water" and kills the run over a flask
                 * that is full. Carried reads every equipment slot.
                 *
                 * One mechanism only: drinking is a timed action and a fresh iact on a vessel
                 * CANCELS the one in progress, so running both interrupts every mouthful. Sipping
                 * is bounded here the way Drink bounds it - each sip is one swallow, and a sip that
                 * moves nothing means the vessel is done. */
                for (int sip = 0; (sip < 30) && (stamina() < 0.9); sip++) {
                    double was = stamina();
                    if (!Carried.drink(gui))
                        break;
                    waitUntil(() -> stamina() > was, 40);
                    if (stamina() <= was)
                        break;
                }
                waitUntil(() -> stamina() > FELL_DRINK_STAMINA, 100);
                if (stamina() <= FELL_DRINK_STAMINA) {
                    /* Ask the FLASK, not the stamina bar.
                     *
                     * This used to read "the meter did not clear 45% inside one wait" as "no water"
                     * and end the whole run on it. The meter is the wrong witness twice over. A
                     * mouthful is worth a few percent and climbs over seconds while chopping is
                     * spending stamina the whole time, so a drink that WORKED can easily leave the
                     * bar below where it started the wait - and {@code AUtils.drinkTillFull} goes
                     * through the client's own drink, which searches open windows and two equipment
                     * slots, so a flask worn anywhere else is invisible to it and invisible reads
                     * exactly like empty. Between them: "couldn't drink to keep chopping (no water)"
                     * over a flask with water in it, reported while chopping.
                     *
                     * -1 is "cannot tell" and is deliberately NOT treated as empty, for the same
                     * reason {@code Carried} exists at all. An unreadable vessel gets the benefit of
                     * the doubt and the retry budget; only a reading we actually have, and which is
                     * genuinely low, ends the run. */
                    double left = Carried.waterFraction(gui);
                    if (((left < 0) || (left >= FELL_LOW_WATER)) && (++thirstyRetries <= FELL_DRINK_RETRIES)) {
                        NLog.log(LOG, "fell: stamina still " + stamina() + " but "
                            + ((left < 0) ? "the vessels are unreadable" : ("water is at " + Math.round(left * 100) + "% of capacity"))
                            + " - drinking again rather than ending the run (" + thirstyRetries + "/" + FELL_DRINK_RETRIES + ")");
                        continue;
                    }
                    // Genuinely dry, or out of patience - don't limp on to the next tree; stop the run.
                    NLog.log(LOG, "fell: couldn't restore stamina, water at "
                        + ((left < 0) ? "unknown" : (Math.round(left * 100) + "%")) + " - fatal");
                    fatalStop = "Auto-LP stopped: couldn't drink to keep chopping (no water).";
                    return findGob(id) == null;
                }
                thirstyRetries = 0;
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
        /* Around the tidy-up as well as around the walk, because "turns away then turns back" is
         * still unexplained and a 7u move toward the target cannot be it. The remaining candidate
         * is that the character is still finishing the PREVIOUS action's leftover movement while
         * this eats or drops - so what reads as turning away and back is really finishing the last
         * move and then setting off. Dropping and eating are pure UI, so any movement here is
         * inherited, and that is exactly what wants naming. */
        haven.Coord2d pt = here();
        tidyInventory();
        trail("the inventory tidy-up - INHERITED FROM THE LAST ACTION",
            pt, here(), task.isItem() ? null : task.gob);

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
            /* The position trail. Reported repeatedly and never yet pinned down: after finishing at
             * one object the character sets off, stops, and only then takes a proper route - "turns
             * around and turns back". Two things could do that and they want opposite fixes. Either
             * the SERVER is moving us (a raw right-click on a gob is also a move-to, and it walks in
             * a straight line through anything), or we are issuing two paths and the second cancels
             * the first.
             *
             * They are indistinguishable in the log as it stands, so record where we actually are at
             * each step. tidyInventory and rclickGob are not supposed to move the character at all,
             * so ANY line from those two stages is the answer. Only real movement prints. */
            haven.Coord2d p0 = here();
            if (!walkTo(gob)) {
                // Either it outran us, it despawned, or wildlife got in the way. Don't right-click
                // from out of range: the menu wouldn't open, and that would burn a menu-fail retry
                // on something that isn't a menu problem.
                if (hazardBlocked)
                    defer(task);          // temporary - the beast moves, the target is still good
                else if (findGob(gob.id) != null)
                    cannotWalkTo(task);
                return false;
            }
            haven.Coord2d p1 = here();
            trail("the walk", p0, p1, gob);
            openMenuOn(gob);
            haven.Coord2d p2 = here();
            /* Expected now, and not a warning. This stage used to be a raw click that moved nothing
             * itself; it is now openMenuOn, which walks the last gap on PURPOSE when we are further
             * out than CLICK_CLOSE. Measured after that change: 44 moves here, mean 12u, ending a
             * mean 6.7u from the target - which is the pathfinder doing the job the server used to
             * do badly. Left in because the distance is worth watching, not because it is suspect.
             *
             * The canary moved to the menu-wait line below: that one is still supposed to be
             * silent, and it went 39 -> 0 when this replaced the raw click. */
            trail("closing the last gap", p1, p2, gob);
            fm = findFlowerMenu();
            trail("waiting for the flower menu - NOBODY ASKED FOR THIS MOVE", p2, here(), gob);
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
                // Honest about what happens next: nothing in-place - the plan rebuild re-offers
                // the target (the counter above decides when to stop retrying it).
                NLog.log(LOG, "no flower menu for " + task + " (attempt " + menuFails.getOrDefault(
                    task.isItem() ? "i" + System.identityHashCode(task.item) : "g" + task.gob.id, 0)
                    + " of " + MENU_FAIL_LIMIT + ") - leaving it for the next plan");
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
        waitUntil(() -> false, DISCOVERY_SETTLE_TICKS);  // ~0.3s settle so the discovery registers first
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
        waitUntil(() -> gui.vhand == null, HAND_OPERATION_TICKS);
    }

    private void dropToGround(WItem wi) throws InterruptedException {
        wi.item.wdgmsg("take", Coord.z);
        waitUntil(() -> gui.vhand != null, HAND_OPERATION_TICKS);
        Gob p = player();
        if (p != null)
            gui.map.wdgmsg("drop", Coord.z, p.rc.floor(posres), 0);
        waitUntil(() -> gui.vhand == null, HAND_OPERATION_TICKS);
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
                waitUntil(() -> Widgets.find(gui.ui.root, FlowerMenu.class) == null, 50);
                return true;
            }
        }
        return false;
    }

    private void closeMenu(FlowerMenu fm) throws InterruptedException {
        fm.wdgmsg("cl", -1);
        waitUntil(() -> Widgets.find(gui.ui.root, FlowerMenu.class) == null, 50);
    }

    /** Polls briefly for a flower menu to open (the rclick lands asynchronously). */
    private FlowerMenu findFlowerMenu() throws InterruptedException {
        return Widgets.awaitFlowerMenu(gui.ui.root, () -> active && !stop);
    }

    /**
     * The same, without waiting - "is one open right now".
     *
     * {@link #findFlowerMenu} blocks until a menu appears or the bot stops, which is right when a
     * menu is expected and wrong for asking whether one already arrived. {@link #openMenuOn} needs
     * the latter: it has to decide whether the pathfinder's own click produced a menu before
     * falling back to a raw one, and blocking there would wait out the very case it is testing for.
     */
    private FlowerMenu findFlowerMenuNow() {
        for (haven.Widget w = gui.ui.root.child; w != null; w = w.next) {
            if (w instanceof FlowerMenu)
                return (FlowerMenu) w;
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
        /* Learn it, not just report it - but learn it about THIS GOB, and let the planner decide
         * when enough gobs agree to call it a fact about the species.
         *
         * The menu is the authority on what the object in front of us offers, and that is not the
         * same as what its species offers. A tree whose bark has already been taken offers no
         * "Take bark" either, so one sighting cannot tell "junipers have no bark" from "this
         * juniper has been stripped" - and the observation this mechanism was built on was a
         * single grove, which is exactly the ambiguous case. Generalising it stopped bark on every
         * tree of the species, including the ones that still had it.
         *
         * The gob id goes with the record now; see LpPlanner.LACK_CONFIRMATIONS.
         *
         * Recorded before the once-per-resource report below, because that report returns early on
         * the second sighting and the learning must not depend on being the first.
         *
         * Literal options only. SEED_PICK is a placeholder resolved against the live menu, so its
         * absence here says nothing at all. */
        if (!task.isItem() && (task.gob != null)) {
            String res = LpExplorer.resname(task.gob);
            for (String opt : task.options) {
                if (!LpPlanner.SEED_PICK.equals(opt) && !hasOpt(fm, opt))
                    LpPlanner.menuLacks(res, opt, task.gob.id);
            }
        }
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

    /**
     * Walks to a gob, re-pathing as it moves.
     *
     * The movement loop this bot used to carry is the seam's {@code Approach} now (BotNav.approach
     * in haven.automated.nbots.world was split out of the very walkToward this class used to copy):
     * the drift re-path, the keep-out rings, the retreat from a beast, the bulk-aware arrival test,
     * and two things the copy never grew - a path that dies short of a static target is re-issued
     * rather than waited out, and an outright refusal gets the character stepped clear first.
     *
     * The keep-out source is the beasts-only one. The seam default adds other characters'
     * personal space when {@code avoidOthers} is on; this bot publishes only the wildlife rings,
     * exactly as it did before the merge - nobody has asked it to route around people.
     *
     * @return true if we ended up within reach of the target.
     */
    private boolean walkTo(Gob gob) throws InterruptedException {
        hazardBlocked = false;
        try {
            return nav.approach(gob, BotNav.REACH);
        } finally {
            hazardBlocked = nav.hazardBlocked;
        }
    }

    private void rclickGob(Gob gob) {
        gui.map.wdgmsg("click", Coord.z, gob.rc.floor(posres), 3, 0, 0, (int) gob.id,
            gob.rc.floor(posres), 0, -1);
    }

    /**
     * How far off an object's FACE a raw right-click is still safe to send.
     *
     * Measured from the face, not the centre - see {@link #openMenuOn}, which subtracts the
     * target's bulk before comparing. The two differ by up to nine units, which is most of a tile,
     * and using the centre had the bot pathing at boulders it was already touching.
     *
     * Four units, not the eleven it started as. The point of the threshold is that there is no
     * meaningful distance left for the server to drag us through, and a whole tile plainly is:
     * a raw click is a move order on an uncollided line, so from a tile out it walks through
     * whatever lies in that tile. That is the "walks through a solid object after picking"
     * report.
     *
     * Not zero, and the fallback stays. The pathed approach opens no menu about seven times in a
     * hundred, and each of those becomes a menu-fail retry that retires the target after three -
     * so a threshold of zero trades a visible drag for invisible lost work. Four units off the
     * face keeps the cheap path for the genuinely-adjacent case, where the drag is a third of a
     * tile and cannot cross into a neighbouring object.
     */
    private static final double CLICK_CLOSE = 4.0;

    /**
     * Opens a gob's flower menu without handing the last few units to the server.
     *
     * A raw click is a MOVE ORDER, and the basic movement line is not collision aware - that is
     * the entire reason {@code haven.automated.pathfinder} exists. So right-clicking a gob we are
     * not yet standing at tells the server to close the distance in a STRAIGHT LINE, through
     * whatever is in the way, and the route we so carefully planned is discarded for the last
     * stretch of it.
     *
     * Measured, 46 actions in one run: the character moved on 39 of them while waiting for the
     * menu, mean 7.5u, always toward the target. Not during the click - the click returns at once
     * and the server's walk arrives asynchronously, which is why it looked like the menu wait was
     * to blame and why it took an instrument to place it.
     *
     * So when we are further off than {@link #CLICK_CLOSE}, hand the whole thing to the client
     * pathfinder instead: {@code clickb=3} makes it walk - around things - and then issue the
     * interaction itself on arrival. Inside that range the raw click stays, because there is no
     * meaningful distance left for the server to drag us through.
     *
     * The fallback matters: a pathfinder approach can end without a menu (an empty path issues no
     * click at all). Falling back to the raw click keeps the old behaviour as the floor rather
     * than turning a cosmetic problem into a stuck bot.
     */
    private void openMenuOn(Gob gob) throws InterruptedException {
        Gob me = player();
        double d = (me == null) ? 0 : me.rc.dist(gob.rc);
        /* From the FACE of the thing, not from its centre.
         *
         * The centre is the wrong origin and it is why the bot kept pathing at objects it was
         * already leaning on. Bulk runs from about 2 units for a bush to 9 for a boulder or a
         * felled log, so "ten units from the centre" is a tile away from a raspberry bush and
         * one unit off the rock face - the same number, opposite situations, and only the second
         * one has anything left to walk. Measured over one run: stops divide cleanly into a
         * cluster one to three units off the face, some of them touching it (a negative gap, the
         * disc overlapping the modelled box), and a second cluster ten to eighteen out. Only the
         * far cluster is worth a path. The near one was getting one anyway, and each of those is
         * a search, a wait, and a fair share of the eight approaches a run that come back with no
         * menu at all.
         *
         * It also closes a gap between two ideas of "close enough" that never agreed: the walk
         * accepts arrival anywhere inside REACH (22) of the centre, and this then re-pathed above
         * 4 of the centre - so almost every arrival was followed by another path. Reach is what
         * decides whether the menu will open; the face is what decides whether there is any
         * distance left to cross. */
        double face = (me == null) ? 0 : haven.automated.nbots.world.BotNav.faceGap(gob, me.rc);
        if (face <= CLICK_CLOSE) {
            rclickGob(gob);
            return;
        }
        NLog.log(LOG, "  " + (int) face + "u off its box (" + (int) d + "u to centre)"
            + " - walking the last gap with the pathfinder rather"
            + " than letting the click drag us there through whatever is in the way");
        gui.map.pfRightClick(gob, -1, 3, 0, null);
        waitUntil(() -> !nav.walking(), 60);
        if (findFlowerMenuNow() == null) {
            NLog.log(LOG, "  the pathfinder approach opened no menu - falling back to a direct click");
            rclickGob(gob);
        }
    }

    private void rclickAndChoose(Gob gob, String option) throws InterruptedException {
        if (gob == null)
            return;
        /* Through {@link #openMenuOn}, not a bare {@link #rclickGob}, and this was the last place
         * still sending the raw one from a distance.
         *
         * A raw right-click is a MOVE ORDER on a movement line that is not collision aware, so
         * clicking a gob we are not standing at hands the last stretch to the server, which closes
         * it in a straight line through whatever is in the way - no client route, no path line.
         * openMenuOn already guards that with {@link #CLICK_CLOSE} and the harvest path has used it
         * since the fix; this one, the CHOPPING path, kept the old call and so kept the old bug.
         * The evidence they are the same bug: the harvest path's instrument
         * ("NOBODY ASKED FOR THIS MOVE") fell from 39 in a run to 1 when it was routed this way,
         * while chopping went on issuing the click cold.
         *
         * Felling is where it shows worst, because the swing loop re-clicks the same tree many
         * times and the arrival bound deliberately accepts a stop up to REACH (22u) out. */
        openMenuOn(gob);
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
            // The LOG view, not the gob's live availability bits: a pick clears the seed/leaf bit
            // the moment it lands, while the item-parented LpLog entry lands a beat later. Reading
            // the bits would report "discovered something" before the record exists, letting the
            // planner slip in one redundant walk (and, if the item never registers at all, treat a
            // real gap as closed). See LpExplorer.logUndiscoveredProducts.
            return LpExplorer.logUndiscoveredProducts(gob);
        } catch (Loading l) {
            return Collections.emptyList();
        }
    }

    /**
     * How far from the target we are willing to END UP, in WORLD UNITS. Fifty is a bit under five
     * tiles - close enough to work whatever it is, far enough that the near bank of a stream or
     * the walkable side of a wide trunk still counts.
     *
     * UNITS, despite what this said for two rounds. It fed a bounding box for the reachability
     * search once, and was documented in tiles for that job; the box is gone - {@link
     * haven.automated.nbots.world.Router#reachable} bounds itself by its own search ceiling now -
     * and the number's only remaining use is the radius passed to {@code standableAround}, which
     * divides by {@code MCache.tilesz} and so reads it as units. Fifty tiles and fifty units are
     * both plausible-looking radii, which is why nothing caught it: the value never changed, only
     * what it meant, and a unit changing under a constant is not a compile error.
     *
     * Whatever this is, it must be the SAME value passed to {@link
     * haven.automated.nbots.world.Router#groundedAround} - that method answers whether reachable
     * could mean anything, and a different margin makes it answer about a different question.
     */
    private static final int REACH_MARGIN = 50;

    /**
     * Whether there is a way to this target on foot.
     *
     * The chase itself cannot answer this. It re-aims at the target every few seconds with the
     * local pathfinder, which sees 88 tiles - so it has no way to hold a detour, and anything it
     * cannot reach in a roughly straight line it simply walks at until the attempt budget is gone.
     * That is what happened at an apple across a river.
     *
     * Asked of the router, which reads the map file and the learned walls. Two things fall out of
     * the one question: a river is refused however it bends - a straight-line water check, which is
     * what this replaced, can run over dry ground while every way round is wet - and so is anything
     * standing inside a palisade, since an enclosure has no route into it that does not go through
     * a gateway. The base's own timber is not for collecting, and now it is not collected.
     */
    private String whyUnwalkable(LpTask task) {
        if (task.isItem() || task.gob == null)
            return null;  // already in the pack; there is no walk to fail
        Gob target = task.gob;
        /* Standing at it already, so there is no walk to prove. Everything below answers "could we
         * get there", and we are there.
         *
         * This is the common case and it was paying full price for it: one gob routinely carries
         * several tasks - a log offers boards AND blocks, and they are separate tasks because they
         * need different tools - so after acting on the first, the second went through the enclosure
         * test and a reachability search before walkToward returned true on its first line without
         * moving. That is the pause between picking one thing and picking the next off the same
         * object, which looks exactly like the bot re-pathing to something it is standing on. */
        Gob here = player();
        if ((here != null) && (here.rc.dist(target.rc) <= BotNav.REACH))
            return null;
        try {
            /* Inside somebody's wall is refused on its own terms rather than as a routing failure,
             * because routing would not call it one: a gateway counts as passable there, since
             * opening one is the travel layer's job, so everything in a base is reachable and
             * always will be. This bot has no gate handling and should not grow any - what is
             * inside a palisade was put there, and is the base's timber rather than forage. */
            if (haven.automated.nbots.world.Barriers.walledOffFrom(gui, target.rc))
                return "it is inside a wall we are not inside";
            /* Said out loud when the question cannot be answered, because the answer defaults to
             * "go ahead" and a silent default is indistinguishable in the log from a real yes.
             * Which of the two it was decides where to look next: a target the map file cannot
             * place is a map-file problem, while a target it can place and still calls reachable
             * across a river is a terrain-data one. Guessing between them has already cost a
             * round. */
            if (!haven.automated.nbots.world.Router.answerable(gui, target.rc))
                NLog.log(LOG, "cannot tell whether " + LpExplorer.resname(target)
                    + " is reachable - the map file can't place one end yet; trying it anyway");
            /* The other silent yes, and the one that had nothing watching it. A run where the line
             * above never fired was read as "every yes was a real yes"; it is not, because
             * reachable also passes anything it cannot find standable ground beside, on purpose.
             * Until this is counted there is no way to tell a proven route from an unproven one,
             * and the difference is exactly the reported "walks at a wall". */
            else if (!haven.automated.nbots.world.Router.groundedAround(gui, target.rc, REACH_MARGIN))
                NLog.log(LOG, "cannot tell whether " + LpExplorer.resname(target)
                    + " is reachable - no ground we have observed lies beside it; trying it anyway");
            /* Not through gateways, because this bot cannot open one.
             *
             * It chases with the client's own pathfinder and nothing else - no route layer, no gate
             * layer - so a target whose only way in is a shut gate is not a target, it is a wall to
             * walk at until the attempt budget runs out. One logged run did exactly that four times
             * in two minutes, on felled logs inside the palisade, each ruled reachable through a
             * gateway the bot had no way to operate. An open gateway it CAN walk through, and one
             * out of render is taken to be shut - passing over a target costs a target, believing in
             * one costs the whole attempt budget. */
            /* Say WHICH of water, cliff and gateway, because the guess in that sentence was costing
             * rounds. This line went from about seven a run to fifty-three between two builds, and
             * with only the generic wording there was no way to tell a correct new refusal (routing
             * had just been taught to refuse rock and cave) from a fault in the candidate search
             * (which had just changed shape). The router knows; it was only ever asked yes or no. */
            String[] why = new String[1];
            if (!haven.automated.nbots.world.Router.reachable(gui, target.rc, REACH_MARGIN, false, why))
                return "no way to it on foot - " + ((why[0] == null) ? "reason unknown" : why[0]);
            return null;
        } catch (Loading l) {
            return null;  // ask again next attempt rather than retiring on a half-loaded map
        }
    }

    /** Where the character is this instant, or null if it has not loaded. */
    private haven.Coord2d here() {
        Gob me = player();
        return (me == null) ? null : me.rc;
    }

    /**
     * Says that the character moved during a stage, and how far.
     *
     * Prints ONLY when it actually moved, so the quiet stages stay quiet and a line is a fact
     * rather than a reading. A metre of slop is ignored - the character drifts a little settling
     * out of a walk, and that is not what this is looking for.
     */
    private void trail(String stage, haven.Coord2d from, haven.Coord2d to, Gob target) {
        if ((from == null) || (to == null))
            return;
        double d = from.dist(to);
        if (d < 1.0)
            return;
        String where = "";
        if (target != null) {
            /* The distance alone cannot tell arrival from pinning, and that is the whole of the
             * "caught clipping the edge of objects" report.
             *
             * The pathfinder carves the target's own collision box out of the map
             * (Pathfinder.excludeGob) so a route can be found INTO it, and the server's move then
             * stops the disc at first contact with the real box. Stopping at the face is correct
             * and is what most of these are - but a diagonal approach contacts a CORNER, where the
             * disc pins further out than the face, and on a long thin box (a felled log, the end of
             * a palisade) that pin can be well away from the gob. Both read as "now 7u from it".
             *
             * So print what the number should be compared against. A stop at about the bulk is the
             * face and is right; a stop consistently past it, especially where the menu then fails,
             * is the pin - and only then is it worth changing where the walk aims, which is a
             * movement change and not one to make on a hunch. */
            double gap = to.dist(target.rc);
            double face = haven.automated.nbots.world.BotNav.faceGap(target, to);
            where = " (now " + (int) gap + "u to centre, " + (int) face + "u off its box"
                + ((face > CLICK_CLOSE) ? " - PAST THE FACE" : "") + ")";
        }
        NLog.log(LOG, "  moved " + (int) d + "u during " + stage + where);
    }

    private void retire(LpTask task) {
        for (String opt : task.options)
            exhausted.add(task.key(opt));
    }

    /** How many times a target may fail to be WALKED to before we stop believing in it. */
    private static final int WALK_TRIES = 2;

    /** Targets we have failed to walk to, and how often, so the last try can retire them. */
    private final java.util.Map<Long, Integer> unwalkable = new java.util.HashMap<>();

    /**
     * A target we could not walk to: set aside and tried again, and only spent once it has failed
     * repeatedly.
     *
     * Retiring on the first failed walk was throwing away good resources for the rest of the
     * session. {@link #retire} marks every option {@code exhausted} PERMANENTLY, and the thing that
     * sent it there is a local pathfinder that ran out of attempts - which is a statement about one
     * moment, not about the target. The log has this happening to a flint the bot walks past
     * perfectly well seventeen seconds later; the difference between the two was where it happened
     * to be standing, and where it is standing is the one thing guaranteed to have changed by the
     * next attempt.
     *
     * Twice, though, not for ever. Something genuinely unreachable - the far bank of a river that
     * {@code reachable} did not catch - would otherwise be retried every plan for the whole session,
     * and a target that has beaten us from two different places has earned being written off.
     */
    private void cannotWalkTo(LpTask task) {
        if (task.isItem() || task.gob == null) {
            retire(task);
            return;
        }
        int failed = unwalkable.merge(task.gob.id, 1, Integer::sum);
        if (failed >= WALK_TRIES) {
            NLog.log(LOG, "couldn't reach " + task + " on " + failed
                + " separate attempts - retiring it for good");
            retire(task);
            return;
        }
        NLog.log(LOG, "couldn't reach " + task + " (attempt " + failed + " of " + WALK_TRIES
            + ") - setting it aside rather than spending it; we will be standing somewhere else"
            + " next time");
        blockedBy(task);
        defer(task);
    }

    /**
     * Names whatever the record thinks is in the way of a target we could not reach.
     *
     * The same dump that turned "the bot walks into things" into "the bot is in an orchard and
     * aiming at a tile with a lemon tree on it" for the cleanup bot. One failure a session is far
     * too thin to design against, and until now nothing recorded what actually stopped the walk -
     * only that it stopped.
     */
    private void blockedBy(LpTask task) {
        try {
            Gob me = player();
            if ((me == null) || (task.gob == null))
                return;
            NLog.log(LOG, "  it is " + (int) me.rc.dist(task.gob.rc) + "u away; what our record"
                + " says is between us and it:");
            NLog.log(LOG, haven.automated.nbots.world.Probe.map(gui, task.gob.rc, 12));
            NLog.log(LOG, haven.automated.nbots.world.Probe.objectsNear(gui, task.gob.rc, 8));
        } catch (RuntimeException e) {
            // Diagnostics must never be the reason a shift dies.
        }
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

    /** Ticks to wait for the hand to empty or fill during a drop/pickup operation. */
    private static final int HAND_OPERATION_TICKS = 50;

    /**
     * Sleep-polling wait: checks the condition first (so an already-true condition returns with no
     * delay), then sleeps POLL_MS between checks, up to maxTicks. The 25ms granularity keeps the
     * bot responsive between actions - at 50ms the gaps were visibly laggier than nurgling's
     * event-driven waits.
     *
     * Runs through the seam's MovementCommand now: the loop, the loading tolerance and the
     * throw-on-stop are all the same as this bot's copy, and they live in one place with the rest
     * of the movement stack instead of beside it.
     */
    private void waitUntil(Cond cond, int maxTicks) throws InterruptedException {
        nav.waitUntil(cond::check, maxTicks);
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
