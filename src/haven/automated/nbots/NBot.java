package haven.automated.nbots;

import haven.Button;
import haven.Coord;
import haven.GameUI;
import haven.Label;
import haven.Loading;
import haven.UI;
import haven.Utils;
import haven.Widget;
import haven.Window;
import haven.automated.nbots.core.BotCtx;
import haven.automated.nbots.core.BotSettings;
import haven.automated.nbots.core.NBotConfig;
import haven.automated.nbots.core.NLog;
import haven.automated.nbots.core.Outcome;
import haven.automated.nbots.core.UiWatchdog;
import haven.automated.nbots.task.FillWater;
import haven.automated.nbots.task.ToolSwap;
import haven.automated.nbots.task.Upkeep;
import haven.automated.nbots.world.BotNav;
import haven.automated.nbots.world.WorkClaims;
import haven.automated.pathfinder.Map;

import java.awt.Color;
import java.util.Objects;

/**
 * The window, the thread and the lifecycle - and deliberately nothing else.
 *
 * This used to carry the work helpers too: approach a gob, take a slot, open its menu, watch the
 * pose until the action finished. Those are what two bots ended up duplicating, because a helper
 * that lives on the base class can only be used by being a bot. They are now
 * {@link haven.automated.nbots.task tasks}, and what remains here is the part that genuinely is
 * per-window: the Start/Stop button, the status line, the thread that survives a thrown task, and
 * the process-wide settings that must be torn down whatever happens.
 *
 * A subclass supplies {@link #work()} - one complete shift - and gets {@link #ctx} to run tasks
 * against. Vitals are not checked here either; {@link Upkeep} is a task the shift runs when it
 * suits it, since a bot mid-swing and a bot between targets want to be interrupted at different
 * moments.
 *
 * These are separate classes from Hurricane's own Cellar Digging / Cleanup / Ocean Scouting bots
 * rather than rewrites of them. The stock ones stay exactly as they are under the Bots tab - what a
 * player expects from that button - and leaving them untouched is also what keeps the next upstream
 * update from turning into a hand-merge.
 */
public abstract class NBot extends Window implements Runnable {
    protected final GameUI gui;
    protected final BotNav nav;
    protected final BotCtx ctx;
    protected final ToolSwap tools;
    protected final BotSettings settings;
    protected final String log;

    private final String id;
    private volatile boolean stopped = false;
    private volatile boolean active = false;

    private final Label status;
    private final Button startButton;

    /** Set when something unrecoverable happens mid-shift; the loop reports it and stops. */
    protected volatile String fatalStop = null;

    protected NBot(GameUI gui, String id, String title, String log, Coord size) {
        super(size, title);
        this.gui = gui;
        this.id = id;
        this.log = log;
        this.nav = new BotNav(gui, this::running, log);
        this.ctx = new BotCtx(gui, nav, log, this::running, this::setStatus);
        this.tools = new ToolSwap(ctx);
        this.settings = new BotSettings(id);

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
                        beginShift();
                        stock();
                        Outcome o = work();
                        if (o != null && !o.isOk())
                            fatalStop = o.reason;
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
                        endShift();
                    }
                    stopRunning();
                    if (fatalStop != null) {
                        gui.error(title() + ": " + fatalStop);
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

    /** One complete shift: runs until there's nothing left to do, or Stop is pressed. */
    protected abstract Outcome work() throws InterruptedException;

    /** The window's own name, for chat messages. */
    protected abstract String title();

    private boolean prevBlockWater;

    private void beginShift() {
        UiWatchdog.ensureStarted();
        WorkClaims.identify(gui);
        fatalStop = null;
        // Route around water for the length of the shift. The client can swim, so this is opt-in
        // rather than the pathfinder's default; a bot that swims off after a shoreline stump
        // arrives soaked, slowed, and out of reach of everything it meant to do next. Saved and
        // restored rather than simply cleared, so whatever the player had it at survives.
        prevBlockWater = Map.BLOCK_WATER;
        if (NBotConfig.on(NBotConfig.Key.avoidWater))
            Map.BLOCK_WATER = true;
        NLog.log(log, "=== " + title() + " shift start ===");
    }

    private void endShift() {
        Map.BLOCK_WATER = prevBlockWater;
        Map.keepout(null);
        WorkClaims.releaseAll();
        NLog.log(log, "=== " + title() + " shift end ===");
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

    protected void report(String msg) {
        gui.msg(title() + ": " + msg, Color.WHITE);
    }

    /**
     * Tops the water up before setting out, if anything carried has room in it.
     *
     * Starting a shift half-full only buys a shorter first stint - the bot walks out to the work,
     * runs dry sooner than it needed to, and walks all the way back. Doing it here costs the same
     * trip at a point where nothing has been started yet, and costs nothing at all when the
     * containers are already full.
     *
     * Failure is not fatal on purpose: no water place defined yet is a perfectly ordinary state
     * for a bot being tried out for the first time, and refusing to work over it would be a worse
     * answer than working until thirsty.
     */
    private void stock() throws InterruptedException {
        if (!NBotConfig.on(NBotConfig.Key.autoRefillWater) || !FillWater.thirsty(ctx))
            return;
        report("topping up water before starting");
        Outcome o = new FillWater().run(ctx);
        if (!o.isOk())
            NLog.log(log, "couldn't top up before starting: " + o.reason);
    }

    /**
     * Runs the shared upkeep step - drink, eat, come back - and reports whether work can continue.
     *
     * Called by a shift between targets rather than automatically, because "between targets" is the
     * only moment at which walking away is free. Interrupting a half-chopped tree to go and drink
     * costs the whole chop.
     */
    protected boolean upkeep() throws InterruptedException {
        Outcome o = new Upkeep().run(ctx);
        if (o.isOk())
            return true;
        if (o.isBlocked()) {
            // Blocked upkeep is not fatal - it means the trip didn't complete, not that the
            // character can't work - so the shift carries on and will try again next time round.
            NLog.log(log, "upkeep blocked: " + o.reason);
            return true;
        }
        fatalStop = o.reason;
        return false;
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
            if (gui.nbots != null)
                gui.nbots.forget(this);
            reqdestroy();
            return;
        }
        super.wdgmsg(sender, msg, args);
    }

    @Override
    public void reqdestroy() {
        Utils.setprefc("wndc-nbot-" + id, this.c);
        super.reqdestroy();
    }
}
