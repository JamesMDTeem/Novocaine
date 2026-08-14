package haven.automated.scheduler;

import haven.GameUI;
import haven.automated.nbots.BotDef;
import haven.automated.nbots.BotRegistry;

import java.awt.EventQueue;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;

/**
 * Executes one schedule on its own thread.
 *
 * Each bot step opens the bot through BotRegistry, waits its minutes (or for the player to
 * close it early, which ends the step), then closes it again. Wait steps simply sleep. With
 * repeat on, the schedule loops until stopped; without it, one pass.
 *
 * Widget operations are marshalled onto the UI thread; everything else happens here.
 */
public class ScheduleRunner implements Runnable {
    private final GameUI gui;
    private final Schedule schedule;
    private volatile boolean stopped = false;

    /** The step currently executing - index, or -1 between steps / when done. */
    private volatile int step = -1;

    public ScheduleRunner(GameUI gui, Schedule schedule) {
        this.gui = gui;
        this.schedule = schedule;
    }

    public String name() {
        return schedule.name;
    }

    /** The current step index, or -1 when nothing is executing. */
    public int step() {
        return step;
    }

    public boolean stopped() {
        return stopped;
    }

    public void stop() {
        stopped = true;
    }

    public void run() {
        try {
            while (!stopped) {
                for (int i = 0; i < schedule.steps.size() && !stopped; i++) {
                    step = i;
                    Schedule.Step s = schedule.steps.get(i);
                    if (Schedule.Step.WAIT.equals(s.kind)) {
                        sleepMinutes(s.minutes);
                    } else {
                        runBot(s.bot, s.minutes);
                    }
                }
                step = -1;
                if (!schedule.repeat)
                    break;
            }
        } finally {
            step = -1;
        }
    }

    private void runBot(String id, int minutes) {
        boolean opened = onUi(() -> {
            if (!gui.nbots.isOpen(id)) {
                BotDef def = BotRegistry.def(id);
                if (def == null) {
                    gui.error("Schedule step references unknown bot: " + id);
                } else {
                    gui.nbots.toggle(gui, id);
                }
            }
            return gui.nbots.isOpen(id);
        });
        if (!opened)
            // Unknown bot or it refused to open - keep the schedule alive with a short wait.
            sleepMinutes(Math.min(1, minutes));
        long end = System.currentTimeMillis() + minutes * 60_000L;
        while (!stopped && System.currentTimeMillis() < end) {
            sleep(1000);
            if (!botOpen(id))
                // The player closed the bot early - the step is done.
                break;
        }
        onUi(() -> {
            if (gui.nbots.isOpen(id))
                gui.nbots.close(id);
            return null;
        });
    }

    private boolean botOpen(String id) {
        return onUi(() -> gui.nbots.isOpen(id));
    }

    private void sleepMinutes(int minutes) {
        long end = System.currentTimeMillis() + minutes * 60_000L;
        while (!stopped && System.currentTimeMillis() < end)
            sleep(1000);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Runs a check on the UI thread and returns its result; null if the queue is gone. */
    @SuppressWarnings("unchecked")
    private <T> T onUi(Callable<T> callable) {
        final Object[] out = new Object[1];
        try {
            EventQueue.invokeAndWait(() -> {
                try {
                    out[0] = callable.call();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (InvocationTargetException e) {
            return null;
        }
        return (T) out[0];
    }
}
