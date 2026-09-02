package haven.automated.nbots.core;

/**
 * Detects the UI thread freezing and captures a thread dump when it does.
 *
 * Why this exists: in the original nurgling2 client the bot-cancel path froze the client rather
 * than throwing, which a normal crash log can't catch because a deadlock produces no exception.
 * The watchdog works by heartbeat - the UI thread stamps {@link #beat()} every frame while a bot
 * is running, and this daemon notices when those stamps stop arriving. If the UI thread goes
 * quiet for longer than a frame could ever legitimately take, it dumps every thread's stack (via
 * NLog) so the two threads stuck on each other are recorded, then stays quiet until the UI
 * recovers so it dumps once per freeze rather than continuously.
 *
 * It only judges the UI stalled while it's actually supposed to be beating (a bot is running and
 * ticking); an idle client that simply isn't calling beat() is not a freeze.
 */
public class UiWatchdog {
    private static final long STALL_MS = 8000;      // far longer than any real frame or lag spike
    private static final long POLL_MS = 1000;
    /**
     * Consecutive polls the UI thread must be found dead before the client gives up on itself.
     *
     * The check itself is not a heuristic - a thread is alive or it is not - but the FIELD can
     * briefly point at a finished thread while another is being stood up, so a run of readings
     * is required rather than one. Three seconds costs nothing against a client that is already
     * never going to draw another frame.
     */
    private static final int DEAD_POLLS = 3;

    private static volatile long lastBeat = 0;
    private static volatile boolean beating = false;
    private static Thread thread;

    /** Called from the UI thread every frame it's alive. Cheap: two volatile writes. */
    public static void beat() {
        lastBeat = System.currentTimeMillis();
        beating = true;
    }

    /** Called when the last bot stops - stops the watchdog judging an intentionally idle UI. */
    public static void idle() {
        beating = false;
    }

    public static synchronized void ensureStarted() {
        if (thread != null)
            return;
        thread = new Thread(UiWatchdog::loop, "ui-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    private static void loop() {
        int deadPolls = 0;
        boolean dumped = false;
        long lastDump = 0;
        final long DUMP_COOLDOWN_MS = 30000; // 30 seconds between dumps
        while (true) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
            /* A DEAD ui thread, which is a different failure from a slow one and was not
             * being looked for at all.
             *
             * A stalled UI thread is stuck and may come back. A dead one cannot: the loop that
             * draws frames and dispatches input is gone, so the window keeps showing whatever
             * was last rendered - the frame rate in the corner frozen at whatever it read -
             * while audio carries on from its own thread. Windows still lists the process, but
             * closing from the taskbar does nothing, because the close request has to be
             * handled by the thread that no longer exists. The only way out is Task Manager,
             * and by then the logs that would have explained it are usually gone.
             *
             * Checked outside the beating test on purpose. Beating only happens while a bot is
             * ticking, and a UI thread can die with no bot anywhere near it. */
            Thread ui = haven.UILoop.statuithread;
            if ((ui != null) && !ui.isAlive() && !haven.UILoop.stopping) {
                if (++deadPolls >= DEAD_POLLS) {
                    NLog.dumpAllThreads("UI thread is DEAD - the client cannot draw or accept "
                        + "input again. Exiting rather than leaving a window nothing can close.");
                    NLog.log("crash.log", "[UI-WATCHDOG] the UI thread died; the client exited "
                        + "itself. The cause is whatever was logged above this - a dead UI "
                        + "thread is the symptom, never the fault.");
                    /* exit rather than halt, so preferences and window state still get saved. */
                    System.exit(1);
                }
            } else {
                deadPolls = 0;
            }

            if (!beating) {
                dumped = false;
                continue;
            }
            long since = System.currentTimeMillis() - lastBeat;
            long now = System.currentTimeMillis();
            if (since > STALL_MS) {
                if (!dumped || now - lastDump > DUMP_COOLDOWN_MS) {
                    NLog.dumpAllThreads("UI thread appears frozen (" + since + "ms since last frame)");
                    dumped = true;
                    lastDump = now;
                }
            } else {
                dumped = false;  // UI recovered; arm for the next distinct freeze
            }
        }
    }
}
