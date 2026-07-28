package haven.automated.nbots.core;

import haven.GameUI;
import haven.Gob;
import haven.IMeter;
import haven.Loading;
import haven.automated.nbots.world.BotNav;

/**
 * Everything a {@link Task} is allowed to touch, handed to it as one parameter.
 *
 * The alternative - and what nurgling2 does - is for actions to reach for
 * {@code NUtils.getGameUI()} and its friends from anywhere. That reads fine and costs nothing to
 * write, but it means a task's signature says nothing about what it does, no task can be exercised
 * against anything but the live client, and "which things can this touch?" has no answer short of
 * reading the body. Passing a context makes the dependency list the type.
 *
 * It also gives the small shared conveniences a single home: the vitals every task consults before
 * doing anything, the pose check, the status callback. Those were methods on the bot base class,
 * which meant a task could only have them by being a method on a bot - which is precisely the
 * arrangement that produced two copies of the same work loop.
 */
public class BotCtx {
    public final GameUI gui;
    public final BotNav nav;
    /** Log file name, e.g. "nbot-cleanup.log". */
    public final String log;

    private final Abort abort;
    private final Status status;

    public interface Abort {
        boolean running();
    }

    public interface Status {
        void set(String text);
    }

    public BotCtx(GameUI gui, BotNav nav, String log, Abort abort, Status status) {
        this.gui = gui;
        this.nav = nav;
        this.log = log;
        this.abort = abort;
        this.status = status;
    }

    /** False once the bot has been stopped. Every wait in BotNav already checks this. */
    public boolean running() {
        return abort.running();
    }

    public void status(String text) {
        if (status != null)
            status.set(text);
    }

    public void log(String message) {
        NLog.log(log, message);
    }

    // ------------------------------------------------------------------ the character

    public Gob player() {
        return (gui.map == null) ? null : gui.map.player();
    }

    public Gob gob(long id) {
        return gui.ui.sess.glob.oc.getgob(id);
    }

    /** Meters are already 0..1 fractions, not percentages, so these are used directly. */
    public double stamina() {
        IMeter.Meter m = gui.getmeter("stam", 0);
        return (m == null) ? 1.0 : m.a;
    }

    public double energy() {
        IMeter.Meter m = gui.getmeter("nrj", 0);
        return (m == null) ? 1.0 : m.a;
    }

    public double health() {
        try {
            return gui.getmeters("hp").get(1).a;
        } catch (Exception e) {
            return 1.0;
        }
    }

    public boolean poseContains(String s) {
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

    /** Free cells in the pack, or -1 if the inventory isn't ready. */
    public int freeSpace() {
        try {
            return (gui.maininv == null) ? -1 : gui.maininv.getFreeSpace();
        } catch (Exception e) {
            return -1;
        }
    }

    /** True while a progress bar is running - the game's own "still working" signal. */
    public boolean onProgress() {
        try {
            return gui.prog != null && gui.prog.prog >= 0;
        } catch (Exception e) {
            return false;
        }
    }
}
