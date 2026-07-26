package haven.automated.nbots;

import haven.Button;
import haven.Coord;
import haven.FlowerMenu;
import haven.GameUI;
import haven.Gob;
import haven.IMeter;
import haven.Label;
import haven.Loading;
import haven.UI;
import haven.Utils;
import haven.Widget;
import haven.Window;
import haven.automated.AUtils;
import haven.automated.lp.NLog;
import haven.automated.lp.UiWatchdog;
import haven.automated.pathfinder.Map;

import java.awt.Color;
import java.util.Objects;

/**
 * Shared skeleton for the Nurgling-tab bots: the window, the run loop, the vitals, and the
 * decision about what to do when the character runs out of water.
 *
 * These bots are deliberately separate classes from Hurricane's own Cellar Digging / Cleanup /
 * Ocean Scouting bots rather than rewrites of them. The stock ones stay exactly as they are under
 * the Bots tab - they're what a player expects when they press that button, and this fork is
 * maintained as a patch against upstream, so leaving them untouched is also what keeps the next
 * upstream update from turning into a hand-merge.
 *
 * What the subclasses get from here:
 *
 * - A window with a status line and a Start/Stop button, and a run loop that survives a thrown
 *   task (logged, reported, bot stops) instead of killing the thread silently.
 * - {@link #checkVitals}, which is the one place that decides to port home on near-death, stop on
 *   low energy, and - the interesting one - drink. Drinking is where the water refill hooks in:
 *   the character drinks from CARRIED containers (waterskin, flask, jug), and when those are empty
 *   the answer isn't "stop", it's "go and fill them". See {@link WaterService}.
 * - {@link #nav}, the shared pathfinder wrapper.
 * - Water-avoidance and keep-out state that is set up once around a run and always torn down,
 *   since both are process-wide settings that would otherwise leak into the player's own clicks.
 */
public abstract class NBot extends Window implements Runnable {
    protected final GameUI gui;
    protected final BotNav nav;
    protected final WaterService water;
    protected final String log;

    private volatile boolean stopped = false;
    private volatile boolean active = false;

    private final Label status;
    private final Button startButton;
    private final String prefkey;

    /** Set when something unrecoverable happens mid-run; the loop reports it and stops. */
    protected volatile String fatalStop = null;

    /** Below this fraction of stamina the bot breaks off to drink. */
    protected static final double DRINK_STAMINA = 0.40;
    /** Below this fraction of energy the bot stops - it can't feed itself. */
    protected static final double MIN_ENERGY = 0.25;
    /** Below this fraction of health the bot hearths home. */
    protected static final double PANIC_HEALTH = 0.02;

    protected NBot(GameUI gui, String title, String prefkey, String log, Coord size) {
        super(size, title);
        this.gui = gui;
        this.prefkey = prefkey;
        this.log = log;
        this.nav = new BotNav(gui, this::running, log);
        this.water = new WaterService(gui, nav, log);

        status = add(new Label("Idle."), UI.scale(10, 2));
        startButton = add(new Button(UI.scale(size.x - 20), "Start") {
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
        }, UI.scale(10, size.y - 28));
        pack();
    }

    /** True while the bot should keep working. Read by every wait in {@link BotNav}. */
    public boolean running() {
        return active && !stopped;
    }

    // ------------------------------------------------------------------ run loop

    @Override
    public void run() {
        try {
            while (!stopped) {
                if (active) {
                    try {
                        beginRun();
                        runOnce();
                    } catch (InterruptedException e) {
                        // Stop pressed, or the thread was interrupted on close. Not an error.
                        Thread.interrupted();
                    } catch (Loading l) {
                        Thread.sleep(200);
                        continue;
                    } catch (Throwable t) {
                        NLog.crash(getClass().getSimpleName() + " run", t);
                        gui.error(title() + " hit an error (logged to logs/crash.log): " + t);
                    } finally {
                        endRun();
                    }
                    stopRunning();
                    if (fatalStop != null) {
                        gui.error(fatalStop);
                        setStatus("Stopped: " + fatalStop);
                        fatalStop = null;
                    }
                    UiWatchdog.idle();
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException ignored) {
        } finally {
            UiWatchdog.idle();
            WorkClaims.releaseAll();
        }
    }

    /** One complete work session: runs until there's nothing left to do, or Stop is pressed. */
    protected abstract void runOnce() throws InterruptedException;

    /** The window's own name, for chat messages. */
    protected abstract String title();

    /** Called when the window is closed, so the subclass can clear its GameUI field. */
    protected abstract void onClosed();

    private boolean prevBlockWater;

    private void beginRun() {
        UiWatchdog.ensureStarted();
        WorkClaims.identify(gui);
        fatalStop = null;
        // Route around water for the length of the run. The client can swim, so this is opt-in
        // rather than the pathfinder's default; a bot that swims off after a shoreline stump
        // arrives soaked, slowed, and out of reach of everything it meant to do next. Saved and
        // restored rather than simply cleared, so whatever the player had it at survives.
        prevBlockWater = Map.BLOCK_WATER;
        if (NBotConfig.on(NBotConfig.Key.avoidWater))
            Map.BLOCK_WATER = true;
        NLog.log(log, "=== " + title() + " run start ===");
    }

    private void endRun() {
        Map.BLOCK_WATER = prevBlockWater;
        Map.keepout(null);
        WorkClaims.releaseAll();
        NLog.log(log, "=== " + title() + " run end ===");
    }

    protected void stopRunning() {
        active = false;
        synchronized (ui) {
            startButton.change("Start");
        }
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if (active)
            UiWatchdog.beat();
    }

    protected void setStatus(String text) {
        synchronized (ui) {
            status.settext(text);
        }
    }

    // ------------------------------------------------------------------ vitals

    protected double stamina() {
        IMeter.Meter m = gui.getmeter("stam", 0);
        return (m == null) ? 1.0 : m.a;
    }

    protected double energy() {
        IMeter.Meter m = gui.getmeter("nrj", 0);
        return (m == null) ? 1.0 : m.a;
    }

    protected double health() {
        try {
            return gui.getmeters("hp").get(1).a;
        } catch (Exception e) {
            return 1.0;
        }
    }

    /**
     * The per-cycle safety check every bot runs before doing anything else.
     *
     * @return true if it's safe to carry on working. A false return always comes with either a
     *         fatalStop message or an already-issued hearth, so the caller just needs to stop.
     */
    protected boolean checkVitals() throws InterruptedException {
        if (health() < PANIC_HEALTH) {
            NLog.log(log, "health critical - hearthing home");
            gui.error(title() + ": health critical, travelling home!");
            gui.act("travel", "hearth");
            Thread.sleep(8000);
            fatalStop = "health was critical.";
            return false;
        }
        if (energy() < MIN_ENERGY) {
            fatalStop = "energy too low (eat something).";
            return false;
        }
        if (stamina() < DRINK_STAMINA && !restoreStamina())
            return false;
        return true;
    }

    /**
     * Drinks, and if there's nothing left to drink from, goes and refills.
     *
     * The two-step matters because of how drinking actually works: you drink from containers you're
     * CARRYING, and you fill those from a barrel or a body of fresh water. So an empty waterskin
     * isn't the end of the run, it's a trip - which is the whole point of the auto-refill. Only if
     * that trip can't be made (no source known, or it's on another continent) does the bot stop.
     */
    protected boolean restoreStamina() throws InterruptedException {
        AUtils.drinkTillFull(gui, 0.9, 0.9);
        nav.waitUntil(() -> stamina() > DRINK_STAMINA, 60);
        if (stamina() > DRINK_STAMINA)
            return true;

        if (!NBotConfig.on(NBotConfig.Key.autoRefillWater)) {
            fatalStop = "out of water (auto-refill is off).";
            return false;
        }

        setStatus("Out of water - refilling.");
        NLog.log(log, "stamina " + stamina() + " and nothing to drink - going for water");
        WaterService.Result r = water.refill();
        if (r != WaterService.Result.OK) {
            fatalStop = r.message;
            return false;
        }

        AUtils.drinkTillFull(gui, 0.9, 0.9);
        nav.waitUntil(() -> stamina() > DRINK_STAMINA, 60);
        if (stamina() <= DRINK_STAMINA) {
            fatalStop = "refilled but still couldn't drink - is the source actually water?";
            return false;
        }
        return true;
    }

    protected boolean poseContains(String s) {
        Gob p = gui.map == null ? null : gui.map.player();
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

    /** True while the character is doing something - animating, walking, or on a progress bar. */
    protected boolean busy() {
        try {
            if (gui.prog != null && gui.prog.prog >= 0)
                return true;
        } catch (Exception ignored) {
        }
        return nav.walking() || !poseContains("idle");
    }

    protected void report(String msg) {
        gui.msg(title() + ": " + msg, Color.WHITE);
    }

    // ------------------------------------------------------------------ work slots

    /** The slot this bot currently holds, so it can be renewed while working and always released. */
    private WorkSlots heldSlots = null;
    private int heldSlot = -1;
    private long lastRenew = 0;

    /**
     * Reserves somewhere to stand while working {@code target}, and walks there.
     *
     * The two-stage check - is anyone visibly standing there, and has anyone reserved it - is the
     * whole multi-bot story in one place. The first catches every client, including ones that have
     * never heard of this fork; the second catches our own instances in the window between deciding
     * to go somewhere and arriving, which nothing observable can cover.
     *
     * Slots are tried nearest-first, so a bot takes the side of the object it is already coming in
     * from rather than walking around it.
     *
     * @return true if we hold a slot and are standing in it. On false the caller should move on to
     *         another target: this one is full, unreachable, or gone.
     */
    protected boolean takeSlotAt(Gob target) throws InterruptedException {
        releaseSlot();
        if (target == null)
            return false;
        WorkSlots slots = WorkSlots.around(target);
        Gob me = gui.map.player();
        if (slots == null || me == null)
            return false;
        java.util.List<Gob> others = Crowd.others(gui);

        for (int i : slots.nearestFirst(me.rc)) {
            if (!running())
                throw new InterruptedException();
            if (!slots.plausible(gui, i))
                continue;
            // Someone is already standing here. Checked before claiming so we don't reserve a spot
            // that a non-participating client is visibly occupying.
            if (NBotConfig.on(NBotConfig.Key.avoidOthers)
                && Crowd.occupied(others, slots.at(i), Crowd.PERSONAL_SPACE))
                continue;
            if (!WorkClaims.claim(slots.gobid, i))
                continue;

            heldSlots = slots;
            heldSlot = i;
            lastRenew = System.currentTimeMillis();
            if (walkToSlot(target, slots, i))
                return true;
            releaseSlot();
        }
        NLog.log(log, "no free work slot on " + resname(target) + " (#" + target.id + ")");
        return false;
    }

    /**
     * Gets into the reserved slot. Falls back to a plain approach if the exact spot can't be
     * reached: standing a little off the mark and still being in range to work is a better outcome
     * than abandoning a perfectly good target because one square was awkward.
     */
    private boolean walkToSlot(Gob target, WorkSlots slots, int i) throws InterruptedException {
        if (nav.stepTo(slots.at(i), 11 * 1.5) && inRange(target))
            return true;
        if (nav.approach(target, BotNav.REACH))
            return true;
        return inRange(target);
    }

    /** Close enough to right-click a target and have the action land. */
    protected boolean inRange(Gob target) {
        Gob me = gui.map == null ? null : gui.map.player();
        Gob now = (target == null) ? null : nav.gob(target.id);
        return me != null && now != null && me.rc.dist(now.rc) <= 11 * 4.0;
    }

    /** Keeps the reservation alive while a long job runs. Cheap enough to call every poll. */
    protected void renewSlot() {
        if (heldSlots == null)
            return;
        long now = System.currentTimeMillis();
        if (now - lastRenew < WorkClaims.RENEW_MS)
            return;
        lastRenew = now;
        WorkClaims.renew(heldSlots.gobid, heldSlot);
    }

    protected void releaseSlot() {
        if (heldSlots == null)
            return;
        WorkClaims.release(heldSlots.gobid, heldSlot);
        heldSlots = null;
        heldSlot = -1;
    }

    // ------------------------------------------------------------------ flower menus

    /**
     * Right-clicks a gob and picks the first of {@code options} the menu actually offers.
     *
     * Resolved against the LIVE menu rather than assumed from the resource name, and by NAME rather
     * than by index. The stock bots pick option 0 blind, which works right up until an object
     * offers a different first option than expected - at which point the bot performs whatever that
     * happens to be, and on a menu containing "Destroy" that is not a harmless mistake. Choosing by
     * name means an unexpected menu costs one skipped target instead.
     *
     * @return the option that was chosen, or null if the menu never opened or offered none of them.
     */
    protected String rclickAndChoose(Gob gob, String... options) throws InterruptedException {
        if (gob == null)
            return null;
        gui.map.wdgmsg("click", Coord.z, gob.rc.floor(haven.OCache.posres), 3, 0, 0, (int) gob.id,
            gob.rc.floor(haven.OCache.posres), 0, -1);
        FlowerMenu fm = awaitMenu();
        if (fm == null)
            return null;
        for (String opt : options) {
            for (FlowerMenu.Petal petal : fm.opts) {
                if (opt.equals(petal.name)) {
                    fm.wdgmsg("cl", petal.num, 0);
                    nav.waitUntil(() -> liveMenu() == null, 50);
                    return opt;
                }
            }
        }
        NLog.log(log, "no wanted option on " + resname(gob) + " - menu offers " + petals(fm));
        fm.wdgmsg("cl", -1);
        nav.waitUntil(() -> liveMenu() == null, 50);
        return null;
    }

    /** Polls briefly for a flower menu to open - the right-click lands asynchronously. */
    protected FlowerMenu awaitMenu() throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            if (!running())
                throw new InterruptedException();
            FlowerMenu fm = liveMenu();
            if (fm != null)
                return fm;
            Thread.sleep(25);
        }
        return null;
    }

    protected FlowerMenu liveMenu() {
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

    private static String petals(FlowerMenu fm) {
        StringBuilder sb = new StringBuilder("[");
        for (FlowerMenu.Petal p : fm.opts) {
            if (sb.length() > 1)
                sb.append(", ");
            sb.append(p.name);
        }
        return sb.append(']').toString();
    }

    protected static String resname(Gob gob) {
        if (gob == null)
            return null;
        try {
            haven.Resource res = gob.getres();
            return (res == null) ? null : res.name;
        } catch (Loading l) {
            return null;
        }
    }

    /** Drops whatever is stuck on the cursor - a full hand blocks every subsequent interaction. */
    protected void clearHand() throws InterruptedException {
        if (gui.vhand == null)
            return;
        Gob p = gui.map.player();
        if (p != null)
            gui.map.wdgmsg("drop", Coord.z, p.rc.floor(haven.OCache.posres), 0);
        nav.waitUntil(() -> gui.vhand == null, 20);
    }

    // ------------------------------------------------------------------ lifecycle

    public void stop() {
        active = false;
        stopped = true;
        UiWatchdog.idle();
        WorkClaims.releaseAll();
        try {
            nav.cancelWalk();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if ((sender == this) && Objects.equals(msg, "close")) {
            stop();
            onClosed();
            reqdestroy();
            return;
        }
        super.wdgmsg(sender, msg, args);
    }

    @Override
    public void reqdestroy() {
        Utils.setprefc("wndc-" + prefkey, this.c);
        super.reqdestroy();
    }
}
